package com.datagent.memory;

import com.datagent.model.Message;
import com.datagent.model.Message.ToolCall;
import com.datagent.session.SessionStore;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 将记忆改写与工具结果绑定在同一事务。 */
@Service
public class MemoryMutationService {
    private static final String CODE = "code", MESSAGE = "message", REVISION = "revision";
    private static final String REMAINING = "remainingBytes", NEEDED = "bytesNeeded";
    private static final String OPERATION = "operation", ENTRY = "entry";
    private static final String NOT_AUTHORIZED = "NOT_AUTHORIZED", DEFERRED = "DEFERRED", READ_FAILED = "READ_FAILED";
    /** 同一数据源上的记忆 CAS、会话回执与 JSON 协议。 */
    private final MemoryRepository memory;
    private final SessionStore sessions;
    private final ObjectMapper json;

    MemoryMutationService(MemoryRepository memory, SessionStore sessions, ObjectMapper json) {
        this.memory = memory;
        this.sessions = sessions;
        this.json = json;
    }

    public record Execution(Message result, boolean alreadyRecorded, boolean spentWrite) {}

    public static boolean isWrite(String name) {
        return MemoryPolicy.ADD.equals(name) || MemoryPolicy.EDIT.equals(name);
    }

    public static boolean isRead(String name) { return MemoryPolicy.READ.equals(name); }

    @Transactional
    public Execution execute(String runId, int assistantSeq, ToolCall call,
                             MemoryRequestAssembly.RequestMemory request, boolean spent) {
        // 先按现有回复位置查重，再在同一 Run 行锁下判定与补充的先后。
        var guard = sessions.lockMemoryRun(runId);
        var existing = sessions.recordedToolResult(runId, assistantSeq, call);
        if (existing != null) return new Execution(existing, true, consumes(existing));
        if (!guard.active()) throw new IllegalStateException("运行已结束");
        var grant = request.write();
        if (spent || grant == null || !call.name().equals(grant.tool())
                || !argumentsMatch(call, grant.arguments())) {
            return save(runId, fail(call, NOT_AUTHORIZED, "没有当前用户的精确授权。", Map.of()), false);
        }
        if (guard.pending()) {
            return save(runId, fail(call, DEFERRED, "有新补充，取件后再判断是否写入。", Map.of()), false);
        }
        var snapshot = memory.locked(guard.agentKey());
        if (snapshot.revision() != grant.revision()) return conflict(runId, call, snapshot.revision());
        // 用真实用户源消息再次验证授权内容，而不是信任模型组装的文本。
        var source = sessions.memorySource(runId, grant.sourceSeq());
        var checked = MemoryPolicy.authorize(java.util.List.of(new MemoryRequestAssembly.UserInput(
                grant.sourceSeq(), source.content())), snapshot, false).grant();
        if (!grant.equals(checked)) {
            return save(runId, fail(call, NOT_AUTHORIZED, "用户授权不匹配。", Map.of()), false);
        }

        var edit = MemoryPolicy.apply(snapshot, grant);
        if (edit.error() != null) {
            var extra = MemoryPolicy.FULL.equals(edit.error()) ? Map.of(NEEDED, Integer.toString(edit.bytesNeeded())) : Map.<String,String>of();
            return save(runId, fail(call, edit.error(), "无法完成这项记忆变更，正文未修改。", extra), false);
        }
        long revision = snapshot.revision();
        if (!edit.content().equals(snapshot.content())) {
            if (!memory.update(guard.agentKey(), revision, edit.content())) {
                return conflict(runId, call, memory.read(guard.agentKey()).revision());
            }
            revision++;
        }
        var result = success(call, Map.of(OPERATION, edit.operation(), ENTRY, edit.entry(), REVISION,
                Long.toString(revision), REMAINING, Integer.toString(MemoryRepository.LIMIT - MemoryRepository.bytes(edit.content()))));
        return save(runId, result, true);
    }

    public Message read(String agentKey, ToolCall call, boolean allowed) {
        if (!allowed || !argumentsMatch(call, Map.of())) {
            return fail(call, NOT_AUTHORIZED, "这个请求不能调用记忆读取工具。", Map.of());
        }
        try {
            var snapshot = memory.read(agentKey);
            return success(call, Map.of(MemoryPolicy.CONTENT, snapshot.content(), REVISION,
                    Long.toString(snapshot.revision()), REMAINING, Integer.toString(snapshot.remaining())));
        } catch (org.springframework.dao.DataAccessException unavailable) {
            return fail(call, READ_FAILED, "未能核实当前长期记忆。", Map.of());
        }
    }

    private boolean argumentsMatch(ToolCall call, Map<String, String> expected) {
        try {
            return json.readTree(call.arguments()).equals(json.readTree(json.writeValueAsString(expected)));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean consumes(Message result) {
        var payload = json.readTree(result.content());
        return Boolean.TRUE.equals(result.success()) ||
                MemoryPolicy.CONFLICT.equals(payload.path(CODE).asString()) && payload.has(REVISION);
    }

    private Execution conflict(String runId, ToolCall call, long revision) {
        return save(runId, fail(call, MemoryPolicy.CONFLICT, "记忆已更新，请重新查看后明确发起更改。",
                Map.of(REVISION, Long.toString(revision))), true);
    }

    private Execution save(String runId, Message result, boolean spent) {
        sessions.append(runId, result);
        return new Execution(result, false, spent);
    }

    private Message success(ToolCall call, Map<String, String> payload) {
        return Message.result(call, json.writeValueAsString(payload), true);
    }

    private Message fail(ToolCall call, String code, String reason, Map<String, String> extra) {
        var payload = new java.util.LinkedHashMap<>(extra);
        payload.put(CODE, code);
        payload.put(MESSAGE, reason);
        return Message.result(call, json.writeValueAsString(payload), false);
    }
}
