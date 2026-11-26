package com.datagent.v3.session;

import com.baomidou.mybatisplus.extension.service.IService;
import com.datagent.v3.model.Message;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 通过三个 IService 管理一轮对话的事务、消息及读取视图。 */
@Service
@RequiredArgsConstructor
public class SessionStore {
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
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
    /** 消息 JSON 编解码器。 */
    private final ObjectMapper json;

    /** 一轮的显示内容，消息位置即运行内序号。 */
    public record RunView(String id, int turnNo, String status, String failure, Instant startedAt, Instant endedAt,
                          List<Message> messages) {}
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

    public void append(String id, Message message) {
        // 同一 Run 只有一个写入者，唯一约束保护消息顺序。
        var row = new MessageEntity();
        row.setRunId(id);
        row.setSeq(Math.toIntExact(messages.lambdaQuery().eq(MessageEntity::getRunId, id).count()));
        row.setPayload(json.writeValueAsString(message));
        messages.save(row);
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
        var history = previous.stream().map(run -> view(run, grouped)).toList();
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
        if (close(id, SUCCEEDED, null)) {
            append(id, answer);
            if (checkpoint != null) saveCheckpoint(id, checkpoint);
            release(id);
        }
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
        if (close(id, FAILED, reason)) {
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
        var views = history.stream().map(run -> view(run, grouped)).toList();
        return new Snapshot(sessionId, session.getActiveRunId(), views);
    }

    private static RunView view(RunEntity run, Map<String, List<Message>> grouped) {
        return new RunView(run.getId(), run.getTurnNo(), run.getStatus(), run.getFailure(),
                run.getStartedAt(), run.getEndedAt(), grouped.getOrDefault(run.getId(), List.of()));
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
