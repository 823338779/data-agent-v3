package com.datagent.session;

import com.datagent.model.Message;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Getter;

/** 会话、轮次及其事务操作的业务接口。 */
public interface SessionStore {
    String RUNNING = "RUNNING";
    String SUCCEEDED = "SUCCEEDED";
    String FAILED = "FAILED";
    String STEER_LIMIT = "STEER_LIMIT", STEER_CLOSED = "STEER_CLOSED", ID_REUSED = "ID_REUSED";
    String LIMIT = "LIMIT", TERMINAL = "TERMINAL";

    /** 一轮的显示内容，消息位置即运行内序号。 */
    record RunView(String id, int turnNo, String status, String failure, Instant startedAt, Instant endedAt,
                          List<Message> messages, boolean canSteer, String steerCloseReason, List<SteerReceipt> steers) {}
    /** 客户端按消息序号和 Run 状态推导收件显示。 */
    record SteerReceipt(String runId, UUID clientMessageId, String message, String runStatus, Integer messageSeq) {}
    record SteerAcceptance(SteerReceipt receipt, boolean created) {}
    record SteerDrain(List<SteerReceipt> incorporated, boolean closedAtLimit) {}
    record ReplyCommit(int messageSeq, boolean terminal) {}

    @Getter
    final class SteerRejected extends RuntimeException {
        /** HTTP 可稳定匹配的业务错误码和关闭原因。 */
        private final String code;
        private final String reason;

        public SteerRejected(String code, String reason) {
            super(code);
            this.code = code;
            this.reason = reason;
        }
    }
    /** 单个事务快照中的活动运行与全部历史。 */
    record Snapshot(String sessionId, String activeRunId, List<RunView> runs) {}

    /** 侧栏中的会话摘要。
     * @param id 用于打开会话详情的标识
     * @param title 首条提问的前 80 个字符
     * @param updatedAt 会话最近一次活动时间
     */
    record RecentSession(String id, String title, Instant updatedAt) {}

    record MemoryRunGuard(String agentKey, boolean active, boolean pending) {}

    /** 新一轮的模型材料和对应持久化边界。 */
    record ContextInput(int turn, Message user, Checkpoint checkpoint, List<RunView> history) {}
    /** 已成功历史的摘要。 */
    record Checkpoint(int throughTurn, String summary) {}

    List<RecentSession> recent(String agentKey);

    String createSession(String agentKey);

    String beginRun(String sessionId, String userText);

    void countModelCall(String runId, boolean summary);

    void addTokens(String runId, Long input, Long output);

    int append(String id, Message message);

    SteerAcceptance acceptSteer(String sessionId, String runId, UUID clientMessageId, String text);

    SteerDrain drainSteers(String runId, boolean lastBusinessRequest);

    ReplyCommit commitTextReply(String runId, Message reply, Checkpoint checkpoint);

    String memoryOwner(String runId);

    MemoryRunGuard lockMemoryRun(String runId);

    Message recordedToolResult(String runId, int assistantSeq, Message.ToolCall call);

    Message memorySource(String runId, int seq);

    ContextInput context(String id);

    /** 保存不可变归档，路径由 PostgreSQL 生成并限制在当前会话。 */
    String saveArchive(String runId, String body);

    /** 导出到指定轮次为止的成功历史和当前轮次，按原始顺序生成摘要前快照。 */
    List<Message> archiveMessages(String runId, int throughTurn);

    /** 按 Unicode 字符偏移读取归档的一页；越界、跨会话或运行结束时返回 null。 */
    String archivePage(String runId, String path, int offset, int length);

    void finish(String id, Message answer);

    void finishWithContext(String id, Message answer, Checkpoint checkpoint);

    void fail(String id, String reason);

    void fail(String id, String reason, String partial);

    void recover();

    Snapshot snapshot(String sessionId);
}
