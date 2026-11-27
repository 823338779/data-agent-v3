package com.datagent.agent;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import com.datagent.model.*;
import com.datagent.memory.MemoryRequestAssembly;
import com.datagent.persistence.SessionStore;
import com.datagent.tool.execution.ToolExecution;
import com.datagent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AgentRunner.class);
    private static final String FAILED_LOG = "Agent run failed: {}";
    private static final String INSTRUCTION_SEPARATOR = "\n\n";
    private static final int MAX_REQUESTS = 30;
    /** 断流和空回复共用重试次数；断流按两秒、四秒退避。 */
    private static final int MAX_RETRIES = 2;
    private static final String RETRY_LOG = "模型请求中断，第 {} 次重试，runId={}：{}";
    /** 仅在空回复的后续请求中提醒模型交付正文或调用工具。 */
    private static final String EMPTY_REPLY_HINT = "上次响应没有可见回答或工具调用。请继续当前任务：需要工具时发起调用，否则直接给出正文。";
    /** 保存活动运行及完整消息。 */
    private final SessionStore sessions;
    /** 登记工具并校验权限。 */
    private final ToolRegistry tools;
    /** 在运行事务内执行工具并保存回执。 */
    private final ToolExecution toolExecution;
    /** 默认 Agent 的配置。 */
    private final AgentDefinition agent;
    /** 单次模型请求入口。 */
    private final ModelClient model;
    /** 按请求重建压缩视图，内部摘要独立于用户可见流。 */
    private final ConversationContextService contexts;
    /** 为业务模型请求加载记忆快照和提示词。 */
    private final MemoryRequestAssembly memories;

    public void execute(String runId, Consumer<RunEvent> emit) {
        Turn turn = new Turn(runId, emit);
        turn.send(RunEvent.STARTED, null, null, null, null, true, null);
        try {
            // 每次请求都重新校验容量；数据库中的原始聊天不受压缩影响。
            SessionStore.ContextInput initial = sessions.context(runId);
            ConversationContext context = contexts.start(initial);
            // 摘要只有实际请求模型时才计数，预算预检和轻量预览均不计入。
            ModelClient summaryModel = (input, onText) -> complete(runId, true, input, onText);
            String owner = sessions.memoryOwner(runId);
            List<Map<String, Object>> toolDefinitions = tools.definitions();
            int retries = 0;
            boolean retryingEmptyReply = false;
            Message lastSuccessfulTool = null;
            String lastAssistantText = "";
            for (int requestNo = 1; requestNo <= MAX_REQUESTS; requestNo++) {
                // 只有业务请求会消费补充；最后一次取件和关闭入口一起提交。
                SessionStore.SteerDrain drain = sessions.drainSteers(runId, requestNo == MAX_REQUESTS);
                if (drain.closedAtLimit()) {
                    turn.send(RunEvent.STEER_GATE, null, null, null, null, false, SessionStore.LIMIT);
                }
                for (SessionStore.SteerReceipt receipt : drain.incorporated()) {
                    // 新补充不能复用旧任务的回执作为回答。
                    lastSuccessfulTool = null;
                    lastAssistantText = "";
                    retries = 0;
                    retryingEmptyReply = false;
                    turn.messageSeq = receipt.messageSeq();
                    context.appendUser(turn.messageSeq, Message.user(receipt.message()));
                    turn.send(RunEvent.STEER_INCORPORATED, receipt.message(), null, null,
                            receipt.clientMessageId(), null, null);
                    turn.messageSeq++;
                }
                MemoryRequestAssembly.Prepared memory = memories.prepare(owner, requestNo == MAX_REQUESTS);
                String instructions = agent.instructions();
                if (retryingEmptyReply) instructions += INSTRUCTION_SEPARATOR + EMPTY_REPLY_HINT;
                ModelClient.ModelRequest input = contexts.prepare(context, summaryModel, agent,
                        instructions + INSTRUCTION_SEPARATOR + memory.instructions(), toolDefinitions);
                ModelClient.ModelReply reply;
                try {
                    reply = complete(runId, false, input, turn::delta);
                    if (reply.calls().isEmpty() && reply.text().isBlank()) {
                        throw new ModelClient.EmptyResponse("模型未返回回答");
                    }
                } catch (ModelClient.InterruptedResponse failure) {
                    // 只重试尚未交付正文的请求；已执行的工具和结果保留在当前上下文。
                    if (!turn.partial.isEmpty() || retries >= MAX_RETRIES || requestNo == MAX_REQUESTS) throw failure;
                    waitForRetry(runId, ++retries, failure);
                    continue;
                } catch (ModelClient.EmptyResponse empty) {
                    // 已收到 response.completed，使用可见正文或当前工具链的成功摘要。
                    String answer = turn.partial.toString();
                    if (answer.isBlank()) answer = lastSuccessfulTool == null ? "" : tools.resultSummary(lastSuccessfulTool);
                    if (answer.isBlank()) answer = lastAssistantText;
                    // 没有可交付内容时只重试模型请求，已有工具调用和回执保留在上下文中。
                    if (answer.isBlank()) {
                        if (retries >= MAX_RETRIES || requestNo == MAX_REQUESTS) {
                            throw new IllegalStateException("模型未返回回答", empty);
                        }
                        retries++;
                        retryingEmptyReply = true;
                        turn.partial.setLength(0);
                        continue;
                    }
                    reply = new ModelClient.ModelReply(answer, List.of());
                }
                retries = 0;
                retryingEmptyReply = false;
                Message assistant = Message.assistant(reply.text(), reply.calls());
                if (reply.calls().isEmpty()) {
                    ConversationContext.Checkpoint checkpoint = context.checkpoint();
                    SessionStore.ReplyCommit saved = sessions.commitTextReply(runId, assistant, checkpoint == null ? null
                            : new SessionStore.Checkpoint(checkpoint.throughTurn(), checkpoint.summary()));
                    turn.messageSeq = saved.messageSeq();
                    turn.partial.setLength(0);
                    if (saved.terminal()) {
                        turn.send(RunEvent.COMPLETED, reply.text(), null, null);
                        return;
                    }
                    // 不撤回已交付的回答；在同一个 Run 内用补充续答。
                    context.appendReply(saved.messageSeq(), assistant);
                    turn.send(RunEvent.REPLY_COMMITTED, reply.text(), null, null);
                    turn.messageSeq++;
                    continue;
                }
                if (requestNo == MAX_REQUESTS) throw new IllegalStateException("已达到模型调用轮数上限");

                if (reply.calls().stream().map(Message.ToolCall::id).distinct().count() != reply.calls().size()) {
                    throw new IllegalStateException("模型在同一响应中重复使用工具调用 ID");
                }
                // 助手一次返回的全部调用先入库，每个工具结果沿用原始调用 ID。
                turn.messageSeq = sessions.append(runId, assistant);
                ArrayList<Message> batch = new ArrayList<>();
                batch.add(assistant);
                if (!reply.text().isBlank()) lastAssistantText = reply.text();
                turn.partial.setLength(0);
                for (Message.ToolCall call : reply.calls()) {
                    Message result = toolExecution.invoke(runId, turn.messageSeq, call, input.messages(), memory.revision(),
                            () -> turn.send(RunEvent.TOOL_STARTED, null, call, null));
                    batch.add(result);
                    // 失败回执之后，旧摘要和调用前的文字都不能证明这次操作成功。
                    if (Boolean.TRUE.equals(result.success())) {
                        lastSuccessfulTool = result;
                    } else {
                        lastSuccessfulTool = null;
                        lastAssistantText = "";
                    }
                    turn.send(RunEvent.TOOL_FINISHED, null, call, result);
                }
                context.appendBatch(turn.messageSeq, batch, reply.toolReasoning());
                turn.messageSeq += batch.size();
            }
        } catch (RuntimeException error) {
            // 保留已显示的部分文本，失败轮整体从未来模型上下文排除。
            LOG.warn(FAILED_LOG, runId, error);
            String reason = error instanceof IllegalStateException && error.getMessage() != null
                    ? error.getMessage() : "本轮执行失败";
            sessions.fail(runId, reason, turn.partial.toString());
            turn.send(RunEvent.FAILED, reason, null, null);
        }
    }

    private static void waitForRetry(String runId, int retry, ModelClient.InterruptedResponse failure) {
        LOG.warn(RETRY_LOG, retry, runId, failure.getMessage());
        try {
            Thread.sleep(Duration.ofSeconds(2L * retry));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待重试时运行被中断", interrupted);
        }
    }

    private ModelClient.ModelReply complete(String runId, boolean summary, ModelClient.ModelRequest input,
                                            Consumer<String> onText) {
        // 开始请求与用量回报分开落库，中断时保留次数和未知用量。
        sessions.countModelCall(runId, summary);
        return model.complete(input.withUsageListener((in, out) -> sessions.addTokens(runId, in, out)), onText);
    }

    /** 仅属于本次执行的事件和文本位置，多个会话可同时运行。 */
    private static final class Turn {
        private final String runId;
        private final Consumer<RunEvent> emit;
        /** 当前助手响应尚未入库的文本。 */
        private final StringBuilder partial = new StringBuilder();
        /** 轮内事件序号和当前助手消息序号。用户消息占用第零位。 */
        private long seq;
        private int messageSeq = 1;

        Turn(String runId, Consumer<RunEvent> emit) {
            this.runId = runId;
            this.emit = emit;
        }

        synchronized void delta(String chunk) {
            partial.append(chunk);
            send(RunEvent.DELTA, chunk, null, null);
        }

        synchronized void send(String type, String text, Message.ToolCall call, Message result) {
            send(type, text, call, result, null, null, null);
        }

        synchronized void send(String type, String text, Message.ToolCall call, Message result, UUID clientMessageId,
                               Boolean canSteer, String reason) {
            emit.accept(new RunEvent(type, runId, ++seq, messageSeq, text, call, result, clientMessageId, canSteer, reason));
        }
    }
}
