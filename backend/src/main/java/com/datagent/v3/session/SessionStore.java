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
    private static final String HISTORY_WINDOW = "LIMIT 10";
    /** 会话与活动运行指针。 */
    private final IService<SessionEntity> sessions;
    /** 会话中的独立运行记录。 */
    private final IService<RunEntity> runs;
    /** 保存完整的角色消息。 */
    private final IService<MessageEntity> messages;
    /** 消息 JSON 编解码器。 */
    private final ObjectMapper json;

    /** 一轮的显示内容，消息位置即运行内序号。 */
    public record RunView(String id, int turnNo, String status, String failure, Instant startedAt, Instant endedAt,
                          List<Message> messages) {}
    /** 单个事务快照中的活动运行与全部历史。 */
    public record Snapshot(String sessionId, String activeRunId, List<RunView> runs) {}

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

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Message> context(String id) {
        var current = runs.getById(id);
        var previous = runs.lambdaQuery().eq(RunEntity::getSessionId, current.getSessionId())
                .lt(RunEntity::getTurnNo, current.getTurnNo()).eq(RunEntity::getStatus, SUCCEEDED)
                .orderByDesc(RunEntity::getTurnNo).last(HISTORY_WINDOW).list();
        // 先按完整轮次截取，再恢复对话时间顺序。
        var selected = new ArrayList<>(previous.reversed());
        selected.add(current);
        var grouped = readMessages(selected);
        return selected.stream().flatMap(run -> grouped.getOrDefault(run.getId(), List.of()).stream()).toList();
    }

    @Transactional
    public void finish(String id, Message answer) {
        if (close(id, SUCCEEDED, null)) {
            append(id, answer);
            release(id);
        }
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
        var views = history.stream().map(run -> new RunView(run.getId(), run.getTurnNo(), run.getStatus(),
                run.getFailure(), run.getStartedAt(), run.getEndedAt(), grouped.getOrDefault(run.getId(), List.of()))).toList();
        return new Snapshot(sessionId, session.getActiveRunId(), views);
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
