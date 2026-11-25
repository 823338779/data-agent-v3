package com.datagent.v3.agent;

import java.util.*;
import java.util.function.Consumer;
import com.datagent.v3.model.*;
import com.datagent.v3.session.SessionStore;
import com.datagent.v3.tool.ToolRegistry;
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

    public void execute(String runId, Consumer<RunEvent> emit) {
        var turn = new Turn(runId, emit);
        turn.send(RunEvent.STARTED, null, null, null);
        try {
            // 首次从数据库装入完整历史，随后只追加本轮已保存的消息。
            var history = new ArrayList<>(sessions.context(runId));
            var available = tools.visibleTools(agent);
            for (int requestNo = 1; requestNo <= MAX_REQUESTS; requestNo++) {
                var input = new ModelClient.ModelRequest(agent.model(), agent.instructions(), List.copyOf(history), available);
                var reply = model.complete(input, turn::delta);
                var assistant = Message.assistant(reply.text(), reply.calls());
                if (reply.calls().isEmpty()) {
                    if (reply.text().isBlank()) throw new IllegalStateException("模型未返回回答");
                    sessions.finish(runId, assistant);
                    turn.send(RunEvent.COMPLETED, reply.text(), null, null);
                    return;
                }
                if (requestNo == MAX_REQUESTS) throw new IllegalStateException("已达到模型调用轮数上限");

                // 助手一次返回的全部调用先入库，每个工具结果沿用原始调用 ID。
                sessions.append(runId, assistant);
                history.add(assistant);
                turn.partial.setLength(0);
                for (var call : reply.calls()) {
                    var result = tools.invoke(agent, call, () -> turn.send(RunEvent.TOOL_STARTED, null, call, null));
                    sessions.append(runId, result);
                    history.add(result);
                    turn.send(RunEvent.TOOL_FINISHED, null, call, result);
                }
                turn.messageSeq += 1 + reply.calls().size();
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
