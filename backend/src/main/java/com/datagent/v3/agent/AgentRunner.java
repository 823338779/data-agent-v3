package com.datagent.v3.agent;

import java.util.*;
import java.util.function.Consumer;
import com.datagent.v3.model.*;
import com.datagent.v3.session.SessionStore;
import com.datagent.v3.tool.ToolRegistry;
import com.datagent.v3.tool.ConversationRecallTool;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AgentRunner.class);
    private static final String FAILED_LOG = "Agent run failed: {}";
    private static final int MAX_REQUESTS = 8;
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

    public void execute(String runId, Consumer<RunEvent> emit) {
        var turn = new Turn(runId, emit);
        turn.send(RunEvent.STARTED, null, null, null);
        try {
            // 每次请求都重新校验容量；数据库中的原始聊天不受压缩影响。
            var context = contexts.start(sessions.context(runId));
            var recall = new ConversationRecallTool(sessions, runId, json);
            var available = tools.visibleTools(agent, recall);
            for (int requestNo = 1; requestNo <= MAX_REQUESTS; requestNo++) {
                var input = contexts.prepare(context, model, agent, available);
                var reply = model.complete(input, turn::delta);
                var assistant = Message.assistant(reply.text(), reply.calls());
                if (reply.calls().isEmpty()) {
                    if (reply.text().isBlank()) throw new IllegalStateException("模型未返回回答");
                    var checkpoint = context.checkpoint();
                    sessions.finishWithContext(runId, assistant, checkpoint == null ? null
                            : new SessionStore.Checkpoint(checkpoint.throughTurn(), checkpoint.summary()));
                    turn.send(RunEvent.COMPLETED, reply.text(), null, null);
                    return;
                }
                if (requestNo == MAX_REQUESTS) throw new IllegalStateException("已达到模型调用轮数上限");

                // 助手一次返回的全部调用先入库，每个工具结果沿用原始调用 ID。
                sessions.append(runId, assistant);
                var batch = new ArrayList<Message>();
                batch.add(assistant);
                turn.partial.setLength(0);
                for (var call : reply.calls()) {
                    var result = tools.invoke(agent, call, recall, () -> turn.send(RunEvent.TOOL_STARTED, null, call, null));
                    sessions.append(runId, result);
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
            emit.accept(new RunEvent(type, runId, ++seq, messageSeq, text, call, result));
        }
    }
}
