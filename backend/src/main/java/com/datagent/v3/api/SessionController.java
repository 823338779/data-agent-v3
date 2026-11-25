package com.datagent.v3.api;

import com.datagent.v3.agent.AgentDefinition;
import com.datagent.v3.session.SessionStore;
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
    /** 启动会话时绑定的 Agent。 */
    private final AgentDefinition agent;
    /** 会话占用与快照服务。 */
    private final SessionStore sessions;
    /** 独立于 HTTP 连接的运行任务。 */
    private final RunStreamService streams;

    public record Created(String sessionId) {}
    public record Input(String message) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created create() {
        return new Created(sessions.createSession(agent.key()));
    }

    @GetMapping(SESSION)
    public SessionStore.Snapshot snapshot(@PathVariable UUID sessionId) {
        return sessions.snapshot(sessionId.toString());
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
