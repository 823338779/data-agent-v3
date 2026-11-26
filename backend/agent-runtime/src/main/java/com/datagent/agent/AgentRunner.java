package com.datagent.agent;

import java.util.*;
import java.util.function.Consumer;
import com.datagent.model.*;
import com.datagent.memory.MemoryRequestAssembly;
import com.datagent.memory.MemoryMutationService;
import com.datagent.tool.AgentTool;
import com.datagent.session.SessionStore;
import com.datagent.tool.ToolRegistry;
import tools.jackson.databind.ObjectMapper;
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
    /** 保存活动运行及完整消息。 */
    private final SessionStore sessions;
    /** 登记工具并校验权限。 */
    private final ToolRegistry tools;
    /** 默认 Agent 的配置。 */
    private final AgentDefinition agent;
    /** 单次模型请求入口。 */
    private final ModelClient model;
    /** 按请求重建压缩视图，内部摘要独立于用户可见流。 */
    private final ConversationContextService contexts;
    /** 为本轮创建只读消息回查工具。 */
    private final ObjectMapper json;
    /** 按每次模型请求加载的记忆视图与事务执行入口。 */
    private final MemoryRequestAssembly memories;
    private final MemoryMutationService memoryMutations;

    public void execute(String runId, Consumer<RunEvent> emit) {
        var turn = new Turn(runId, emit);
        turn.send(RunEvent.STARTED, null, null, null, null, true, null);
        try {
            // 每次请求都重新校验容量；数据库中的原始聊天不受压缩影响。
            var initial = sessions.context(runId);
            var archive = new ConversationArchive(sessions, runId, json);
            var context = contexts.start(initial, archive);
            // 摘要只有实际请求模型时才计数，预算预检和轻量预览均不计入。
            ModelClient summaryModel = (input, onText) -> complete(runId, true, input, onText);
            var owner = sessions.memoryOwner(runId);
            var originals = new ArrayList<MemoryRequestAssembly.UserInput>();
            originals.add(new MemoryRequestAssembly.UserInput(0, initial.user().content()));
            boolean memoryWritten = false, memoryRead = false;
            for (int requestNo = 1; requestNo <= MAX_REQUESTS; requestNo++) {
                // 只有业务请求会消费补充；最后一次取件和关闭入口一起提交。
                var drain = sessions.drainSteers(runId, requestNo == MAX_REQUESTS);
                if (drain.closedAtLimit()) {
                    turn.send(RunEvent.STEER_GATE, null, null, null, null, false, SessionStore.LIMIT);
                }
                for (var receipt : drain.incorporated()) {
                    turn.messageSeq = receipt.messageSeq();
                    context.appendUser(turn.messageSeq, Message.user(receipt.message()));
                    originals.add(new MemoryRequestAssembly.UserInput(turn.messageSeq, receipt.message()));
                    turn.send(RunEvent.STEER_INCORPORATED, receipt.message(), null, null,
                            receipt.clientMessageId(), null, null);
                    turn.messageSeq++;
                }
                var requestMemory = memories.prepare(owner, originals, memoryWritten, memoryRead, requestNo == MAX_REQUESTS);
                var scoped = new ArrayList<AgentTool>();
                scoped.add(archive);
                scoped.addAll(requestMemory.tools());
                var available = tools.visibleScopedTools(agent.tools(), scoped);
                var input = contexts.prepare(context, summaryModel, agent,
                        agent.instructions() + INSTRUCTION_SEPARATOR + requestMemory.instructions(), available, requestMemory.ignored(), archive);
                if (!archive.bind(input.messages())) scoped.remove(archive);
                var reply = complete(runId, false, input, turn::delta);
                var assistant = Message.assistant(reply.text(), reply.calls());
                if (reply.calls().isEmpty()) {
                    if (reply.text().isBlank()) throw new IllegalStateException("模型未返回回答");
                    var checkpoint = context.checkpoint();
                    var saved = sessions.commitTextReply(runId, assistant, checkpoint == null ? null
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
                var batch = new ArrayList<Message>();
                batch.add(assistant);
                turn.partial.setLength(0);
                for (var call : reply.calls()) {
                    Message result;
                    if (MemoryMutationService.isWrite(call.name())) {
                        turn.send(RunEvent.TOOL_STARTED, null, call, null);
                        // 专用事务负责保存工具回执；此处不能重复追加。
                        var receipt = memoryMutations.execute(runId, turn.messageSeq, call, requestMemory, memoryWritten);
                        memoryWritten |= receipt.spentWrite();
                        result = receipt.result();
                    } else if (MemoryMutationService.isRead(call.name())) {
                        turn.send(RunEvent.TOOL_STARTED, null, call, null);
                        result = memoryMutations.read(owner, call, requestMemory.canRead() && !memoryRead);
                        memoryRead |= Boolean.TRUE.equals(result.success());
                        sessions.append(runId, result);
                    } else {
                        result = tools.invokeScoped(agent.tools(), call, scoped,
                                () -> turn.send(RunEvent.TOOL_STARTED, null, call, null));
                        sessions.append(runId, result);
                    }
                    batch.add(result);
                    turn.send(RunEvent.TOOL_FINISHED, null, call, result);
                }
                context.appendBatch(turn.messageSeq, batch);
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
