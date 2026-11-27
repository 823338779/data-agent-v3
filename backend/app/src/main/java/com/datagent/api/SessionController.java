package com.datagent.api;

import com.datagent.agent.AgentDefinition;
import com.datagent.exception.SessionException;
import com.datagent.persistence.SessionStore;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping(SessionController.SESSIONS)
@RequiredArgsConstructor
public class SessionController {
    static final String SESSIONS = "/api/sessions";
    private static final String SESSION = "/{sessionId}";
    private static final String RUNS = SESSION + "/runs";
    private static final String STEERS = RUNS + "/{runId}/steers";
    /** 启动会话时绑定的 Agent。 */
    private final AgentDefinition agent;
    /** 会话占用与快照服务。 */
    private final SessionStore sessions;
    /** 独立于 HTTP 连接的运行任务。 */
    private final RunStreamService streams;

    public record Created(String sessionId) {}
    public record Input(String message) {}

    /** 将会话操作的业务原因转换为对外的 HTTP 错误响应。 */
    @ExceptionHandler(SessionException.class)
    void sessionError(SessionException error, HttpServletResponse response) throws IOException {
        HttpStatus status = switch (error.reason()) {
            case ACTIVE_RUN -> HttpStatus.CONFLICT;
            case INVALID_STEER -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        response.sendError(status.value(), error.getMessage());
    }

    /** 按最近活动顺序展示当前 Agent 有提问记录的会话。 */
    @GetMapping
    public List<SessionStore.RecentSession> recent() {
        return sessions.recent(agent.key());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created create() {
        return new Created(sessions.createSession(agent.key()));
    }

    @GetMapping(SESSION)
    public SessionStore.Snapshot snapshot(@PathVariable UUID sessionId) {
        return sessions.snapshot(sessionId.toString());
    }

    public record SteerInput(UUID clientMessageId, String message) {}
    public record SteerError(String code, String reason) {}

    @PostMapping(STEERS)
    public ResponseEntity<?> steer(@PathVariable UUID sessionId, @PathVariable UUID runId, @RequestBody SteerInput input) {
        try {
            SessionStore.SteerAcceptance result = sessions.acceptSteer(sessionId.toString(), runId.toString(), input.clientMessageId(), input.message());
            return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.receipt());
        } catch (SessionStore.SteerRejected rejected) {
            int status = SessionStore.STEER_LIMIT.equals(rejected.getCode()) ? 422 : 409;
            return ResponseEntity.status(status).body(new SteerError(rejected.getCode(), rejected.getReason()));
        }
    }

    @PostMapping(value = RUNS, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@PathVariable UUID sessionId, @RequestBody Input input) {
        // 输入和并发冲突须在开始 SSE 响应前报告。
        if (input.message() == null || input.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入消息");
        }
        return streams.start(sessions.beginRun(sessionId.toString(), input.message()));
    }
}
