package com.datagent.v3.session;

import com.baomidou.mybatisplus.extension.service.IService;
import com.datagent.v3.model.Message;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 管理一轮对话的事务、消息、补充收件及读取视图。 */
@Service
@RequiredArgsConstructor
public class SessionStore {
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String STEER_LIMIT = "STEER_LIMIT", STEER_CLOSED = "STEER_CLOSED", ID_REUSED = "ID_REUSED";
    public static final String LIMIT = "LIMIT", TERMINAL = "TERMINAL";
    private static final String LOCK_RUN = "FOR UPDATE";
    private static final int MAX_STEER_BYTES = 2048, MAX_STEERS = 8, MAX_RUN_STEER_BYTES = 8192;
    private static final String LATEST_TURN = "LIMIT 1";
    private static final int CHECKPOINT_FORMAT = 1;
    /** 会话与活动运行指针。 */
    private final SessionPersistence sessions;
    /** 会话中的独立运行记录。 */
    private final IService<RunEntity> runs;
    /** 保存完整的角色消息。 */
    private final IService<MessageEntity> messages;
    /** 模型摘要检查点，不参与聊天显示查询。 */
    private final ContextCheckpointPersistence checkpoints;
    /** 按 Run 持久化的补充收件。 */
    private final SteerPersistence steers;
    /** 消息 JSON 编解码器。 */
    private final ObjectMapper json;

    /** 一轮的显示内容，消息位置即运行内序号。 */
    public record RunView(String id, int turnNo, String status, String failure, Instant startedAt, Instant endedAt,
                          List<Message> messages, boolean canSteer, String steerCloseReason, List<SteerReceipt> steers) {}
    /** 客户端按消息序号和 Run 状态推导收件显示。 */
    public record SteerReceipt(String runId, UUID clientMessageId, String message, String runStatus, Integer messageSeq) {}
    public record SteerAcceptance(SteerReceipt receipt, boolean created) {}
    public record SteerDrain(List<SteerReceipt> incorporated, boolean closedAtLimit) {}
    public record ReplyCommit(int messageSeq, boolean terminal) {}

    @Getter
    public static final class SteerRejected extends RuntimeException {
        /** HTTP 可稳定匹配的业务错误码和关闭原因。 */
        private final String code;
        private final String reason;

        SteerRejected(String code, String reason) {
            super(code);
            this.code = code;
            this.reason = reason;
        }
    }
    /** 单个事务快照中的活动运行与全部历史。 */
    public record Snapshot(String sessionId, String activeRunId, List<RunView> runs) {}

    /** 侧栏中的会话摘要。
     * @param id 用于打开会话详情的标识
     * @param title 首条提问的前 80 个字符
     * @param updatedAt 会话最近一次活动时间
     */
    public record RecentSession(String id, String title, Instant updatedAt) {}

    public List<RecentSession> recent(String agentKey) {
        // 只加载侧栏摘要，消息正文仍通过单会话快照读取。
        return sessions.getBaseMapper().recent(agentKey);
    }

    public String createSession(String agentKey) {
        var session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setAgentKey(agentKey);
        sessions.save(session);
        return session.getId();
    }

    @Transactional
    public String beginRun(String sessionId, String userText) {
        // 条件更新在数据库内占用会话行，竞争失败时不会写入轮次或消息。
        String id = UUID.randomUUID().toString();
        boolean acquired = sessions.lambdaUpdate().eq(SessionEntity::getId, sessionId)
                .isNull(SessionEntity::getActiveRunId).set(SessionEntity::getActiveRunId, id)
                .set(SessionEntity::getUpdatedAt, Instant.now()).update();
        if (!acquired) {
            requireSession(sessionId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话已有运行中的请求");
        }
        var previous = runs.lambdaQuery().eq(RunEntity::getSessionId, sessionId)
                .orderByDesc(RunEntity::getTurnNo).last(LATEST_TURN).one();
        var run = new RunEntity();
        run.setId(id);
        run.setSessionId(sessionId);
        run.setTurnNo(previous == null ? 1 : previous.getTurnNo() + 1);
        run.setStatus(RUNNING);
        runs.save(run);
        append(id, Message.user(userText));
        return id;
    }

    public int append(String id, Message message) {
        // 同一 Run 只有一个写入者，唯一约束保护消息顺序。
        var row = new MessageEntity();
        row.setRunId(id);
        row.setSeq(Math.toIntExact(messages.lambdaQuery().eq(MessageEntity::getRunId, id).count()));
        row.setPayload(json.writeValueAsString(message));
        if (!messages.save(row)) throw new IllegalStateException("消息未保存");
        return row.getSeq();
    }

    @Transactional
    public SteerAcceptance acceptSteer(String sessionId, String runId, UUID clientMessageId, String text) {
        if (clientMessageId == null || text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入补充消息和有效的提交 ID");
        }
        // 锁内先校验归属并查旧收件；关闭或配额耗尽后仍可查询同一笔提交。
        var run = lockRun(runId);
        if (run == null || !sessionId.equals(run.getSessionId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "运行不存在");
        }
        var received = steers.lambdaQuery().eq(SteerEntity::getRunId, runId).list();
        var key = clientMessageId.toString();
        for (var row : received) {
            if (key.equals(row.getClientMessageId())) {
                if (!text.equals(row.getMessage())) throw new SteerRejected(ID_REUSED, null);
                return new SteerAcceptance(receipt(run, row), false);
            }
        }
        var reason = closedReason(run);
        if (reason != null) throw new SteerRejected(STEER_CLOSED, reason);
        int bytes = utf8Bytes(text);
        int used = received.stream().mapToInt(row -> utf8Bytes(row.getMessage())).sum();
        if (bytes > MAX_STEER_BYTES || received.size() >= MAX_STEERS || used + bytes > MAX_RUN_STEER_BYTES) {
            throw new SteerRejected(STEER_LIMIT, null);
        }
        var row = new SteerEntity();
        row.setRunId(runId);
        row.setClientMessageId(key);
        row.setMessage(text);
        if (!steers.save(row)) throw new IllegalStateException("补充未保存");
        return new SteerAcceptance(receipt(run, row), true);
    }

    @Transactional
    public SteerDrain drainSteers(String runId, boolean lastBusinessRequest) {
        // 取件与最后一次请求关门共用同一行锁和提交点。
        var run = requireRunningRun(runId);
        var pending = steers.lambdaQuery().eq(SteerEntity::getRunId, runId)
                .isNull(SteerEntity::getMessageSeq).orderByAsc(SteerEntity::getId).list();
        var incorporated = new ArrayList<SteerReceipt>();
        for (var row : pending) {
            row.setMessageSeq(append(runId, Message.user(row.getMessage())));
            if (!steers.updateById(row)) throw new IllegalStateException("补充序号未保存");
            incorporated.add(receipt(run, row));
        }
        boolean closed = lastBusinessRequest && run.getSteerCloseReason() == null;
        if (closed && !runs.lambdaUpdate().eq(RunEntity::getId, runId)
                .set(RunEntity::getSteerCloseReason, LIMIT).update()) {
            throw new IllegalStateException("补充入口未关闭");
        }
        return new SteerDrain(List.copyOf(incorporated), closed);
    }

    @Transactional
    public ReplyCommit commitTextReply(String runId, Message reply, Checkpoint checkpoint) {
        // 回答、是否续接与最终完成在同一事务内决定，禁止已收件无人消费。
        requireRunningRun(runId);
        int seq = append(runId, reply);
        boolean pending = steers.lambdaQuery().eq(SteerEntity::getRunId, runId)
                .isNull(SteerEntity::getMessageSeq).exists();
        if (!pending) complete(runId, checkpoint);
        return new ReplyCommit(seq, !pending);
    }

    private RunEntity lockRun(String runId) {
        return runs.lambdaQuery().eq(RunEntity::getId, runId).last(LOCK_RUN).one();
    }

    private RunEntity requireRunningRun(String runId) {
        var run = lockRun(runId);
        if (run == null || !RUNNING.equals(run.getStatus())) throw new IllegalStateException("运行已结束");
        return run;
    }

    public record MemoryRunGuard(String agentKey, boolean active, boolean pending) {}

    public String memoryOwner(String runId) {
        var run = runs.getById(runId);
        if (run == null) throw new IllegalStateException("运行不存在");
        return requireSession(run.getSessionId()).getAgentKey();
    }

    /** 与补充收件共用行锁，必须在记忆工具事务中调用。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public MemoryRunGuard lockMemoryRun(String runId) {
        var run = lockRun(runId);
        if (run == null) throw new IllegalStateException("运行不存在");
        var owner = requireSession(run.getSessionId()).getAgentKey();
        var pending = steers.lambdaQuery().eq(SteerEntity::getRunId, runId)
                .isNull(SteerEntity::getMessageSeq).exists();
        return new MemoryRunGuard(owner, RUNNING.equals(run.getStatus()), pending);
    }

    /** 从助手的真实调用序号定位回执；不得将另一次模型请求的同名 ID 当成重放。 */
    public Message recordedToolResult(String runId, int assistantSeq, Message.ToolCall call) {
        var assistant = messageAt(runId, assistantSeq);
        if (assistant == null || !Message.ASSISTANT.equals(assistant.role())) {
            throw new IllegalStateException("找不到工具调用的助手消息");
        }
        int index = assistant.calls().indexOf(call);
        if (index < 0) throw new IllegalStateException("工具调用与落库原文不符");
        var result = messageAt(runId, assistantSeq + index + 1);
        if (result == null) return null;
        if (!Message.TOOL.equals(result.role()) || !call.id().equals(result.toolCallId())) {
            throw new IllegalStateException("工具结果与调用顺序不符");
        }
        return result;
    }

    public Message memorySource(String runId, int seq) {
        var message = messageAt(runId, seq);
        if (message == null || !Message.USER.equals(message.role())) {
            throw new IllegalStateException("找不到原始用户授权");
        }
        return message;
    }

    private static String closedReason(RunEntity run) {
        return RUNNING.equals(run.getStatus()) ? run.getSteerCloseReason() : TERMINAL;
    }

    private static SteerReceipt receipt(RunEntity run, SteerEntity row) {
        return new SteerReceipt(run.getId(), UUID.fromString(row.getClientMessageId()), row.getMessage(),
                run.getStatus(), row.getMessageSeq());
    }

    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 新一轮的模型材料和对应持久化边界。 */
    public record ContextInput(int turn, Message user, Checkpoint checkpoint, List<RunView> history) {}
    /** 已成功历史的摘要。 */
    public record Checkpoint(int throughTurn, String summary) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContextInput context(String id) {
        // 从同一数据库快照读取检查点和成功轮次，不再按固定轮数丢历史。
        var current = runs.getById(id);
        var saved = checkpoints.getById(current.getSessionId());
        var checkpoint = saved != null && saved.getFormatVersion() == CHECKPOINT_FORMAT
                ? new Checkpoint(saved.getThroughTurn(), saved.getSummary()) : null;
        var previous = runs.lambdaQuery().eq(RunEntity::getSessionId, current.getSessionId())
                .gt(RunEntity::getTurnNo, checkpoint == null ? 0 : checkpoint.throughTurn())
                .lt(RunEntity::getTurnNo, current.getTurnNo()).eq(RunEntity::getStatus, SUCCEEDED)
                .orderByAsc(RunEntity::getTurnNo).list();
        var grouped = readMessages(previous);
        var history = previous.stream().map(run -> view(run, grouped, List.of())).toList();
        return new ContextInput(current.getTurnNo(), messageAt(id, 0), checkpoint, history);
    }

    @Transactional(readOnly = true)
    public Message recall(String activeRunId, int turnNo, int seq) {
        // 会话范围由当前 Run 推导，不接受模型指定 sessionId；失败轮不作模型事实来源。
        var active = runs.getById(activeRunId);
        if (active == null || !RUNNING.equals(active.getStatus())) return null;
        var target = runs.lambdaQuery().eq(RunEntity::getSessionId, active.getSessionId())
                .eq(RunEntity::getTurnNo, turnNo).one();
        if (target == null || (!activeRunId.equals(target.getId()) && !SUCCEEDED.equals(target.getStatus()))) return null;
        return messageAt(target.getId(), seq);
    }

    private Message messageAt(String runId, int seq) {
        var row = messages.lambdaQuery().eq(MessageEntity::getRunId, runId)
                .eq(MessageEntity::getSeq, seq).one();
        return row == null ? null : json.readValue(row.getPayload(), Message.class);
    }

    @Transactional
    public void finish(String id, Message answer) {
        finishWithContext(id, answer, null);
    }

    @Transactional
    public void finishWithContext(String id, Message answer, Checkpoint checkpoint) {
        var run = lockRun(id);
        if (run == null || !RUNNING.equals(run.getStatus())) return;
        if (steers.lambdaQuery().eq(SteerEntity::getRunId, id).isNull(SteerEntity::getMessageSeq).exists()) {
            throw new IllegalStateException("不能结束尚有补充待处理的运行");
        }
        append(id, answer);
        complete(id, checkpoint);
    }

    private void complete(String id, Checkpoint checkpoint) {
        if (!close(id, SUCCEEDED, null)) throw new IllegalStateException("运行终态未保存");
        if (checkpoint != null) saveCheckpoint(id, checkpoint);
        release(id);
    }

    private void saveCheckpoint(String id, Checkpoint checkpoint) {
        // 与成功终态一起提交，避免失败 Run 或旧边界覆盖有效摘要。
        var current = runs.getById(id);
        var saved = checkpoints.getById(current.getSessionId());
        int through = checkpoint.throughTurn();
        if (through >= current.getTurnNo() || through <= 0 || checkpoint.summary().isBlank()) {
            throw new IllegalArgumentException("无效的会话摘要边界");
        }
        if (saved != null && saved.getThroughTurn() >= through && saved.getFormatVersion() == CHECKPOINT_FORMAT) return;
        if (!runs.lambdaQuery().eq(RunEntity::getSessionId, current.getSessionId())
                .eq(RunEntity::getTurnNo, through).eq(RunEntity::getStatus, SUCCEEDED).exists()) {
            throw new IllegalArgumentException("会话摘要边界必须是成功轮次");
        }
        var row = new ContextCheckpointEntity();
        row.setSessionId(current.getSessionId());
        row.setThroughTurn(through);
        row.setSummary(checkpoint.summary());
        row.setFormatVersion(CHECKPOINT_FORMAT);
        row.setUpdatedAt(Instant.now());
        if (saved == null) checkpoints.save(row);
        else checkpoints.updateById(row);
    }

    @Transactional
    public void fail(String id, String reason) {
        fail(id, reason, "");
    }

    @Transactional
    public void fail(String id, String reason, String partial) {
        // 先锁定目标 Run，使失败与收件共用提交边界。
        if (lockRun(id) != null && close(id, FAILED, reason)) {
            if (!partial.isEmpty()) {
                append(id, Message.assistant(partial, List.of()));
            }
            release(id);
        }
    }

    private boolean close(String id, String status, String reason) {
        return runs.lambdaUpdate().eq(RunEntity::getId, id).eq(RunEntity::getStatus, RUNNING)
                .set(RunEntity::getStatus, status).set(RunEntity::getFailure, reason)
                .set(RunEntity::getEndedAt, Instant.now()).update();
    }

    private void release(String id) {
        sessions.lambdaUpdate().eq(SessionEntity::getActiveRunId, id)
                .set(SessionEntity::getActiveRunId, null).set(SessionEntity::getUpdatedAt, Instant.now()).update();
    }

    @Transactional
    public void recover() {
        // 单实例接收请求前，原子关闭上个进程的运行并清理占用。
        runs.lambdaUpdate().eq(RunEntity::getStatus, RUNNING).set(RunEntity::getStatus, FAILED)
                .set(RunEntity::getFailure, "服务中断，本轮已结束").set(RunEntity::getEndedAt, Instant.now()).update();
        sessions.lambdaUpdate().isNotNull(SessionEntity::getActiveRunId).set(SessionEntity::getActiveRunId, null)
                .set(SessionEntity::getUpdatedAt, Instant.now()).update();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(String sessionId) {
        var session = requireSession(sessionId);
        var history = runs.lambdaQuery().eq(RunEntity::getSessionId, sessionId).orderByAsc(RunEntity::getTurnNo).list();
        var grouped = readMessages(history);
        Map<String, List<SteerEntity>> receipts = history.isEmpty() ? Map.of() : steers.lambdaQuery()
                .in(SteerEntity::getRunId, history.stream().map(RunEntity::getId).toList())
                .orderByAsc(SteerEntity::getId).list().stream().collect(Collectors.groupingBy(SteerEntity::getRunId));
        var views = history.stream().map(run -> view(run, grouped, receipts.getOrDefault(run.getId(), List.of()))).toList();
        return new Snapshot(sessionId, session.getActiveRunId(), views);
    }

    private static RunView view(RunEntity run, Map<String, List<Message>> grouped, List<SteerEntity> rows) {
        var reason = closedReason(run);
        return new RunView(run.getId(), run.getTurnNo(), run.getStatus(), run.getFailure(),
                run.getStartedAt(), run.getEndedAt(), grouped.getOrDefault(run.getId(), List.of()),
                reason == null, reason, rows.stream().map(row -> receipt(run, row)).toList());
    }

    private Map<String, List<Message>> readMessages(List<RunEntity> history) {
        if (history.isEmpty()) {
            return Map.of();
        }
        // 一次查询多个运行，组内保留消息位置。
        return messages.lambdaQuery().in(MessageEntity::getRunId, history.stream().map(RunEntity::getId).toList())
                .orderByAsc(MessageEntity::getSeq).list().stream().collect(Collectors.groupingBy(MessageEntity::getRunId,
                        Collectors.mapping(row -> json.readValue(row.getPayload(), Message.class), Collectors.toList())));
    }

    private SessionEntity requireSession(String id) {
        var session = sessions.getById(id);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return session;
    }
}
