package com.datagent.tool.execution;

import com.datagent.model.Message;
import com.datagent.model.Message.ToolCall;
import com.datagent.persistence.MemoryRepository;
import com.datagent.persistence.MemoryRepository.Snapshot;
import com.datagent.persistence.SessionStore;

import java.util.List;
import java.util.Map;

import com.datagent.tool.MemoryTool;
import com.datagent.tool.AgentTool;
import com.datagent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 负责工具调用的运行状态、回执与事务边界。 */
@Component
@RequiredArgsConstructor
public class ToolExecution {
    private static final String CODE = "code", MESSAGE = "message", DEFERRED = "DEFERRED";
    /** 定位运行及调用回执，操作对应 Agent 的记忆，序列化运行时失败回执。 */
    private final SessionStore sessions;
    private final MemoryRepository memory;
    private final ToolRegistry tools;
    private final ObjectMapper json;

    @Transactional
    public Message invoke(String runId, int assistantSeq, ToolCall call, List<Message> messages, long memoryRevision, Runnable onStart) {
        // 锁定 Run 并按助手消息位置查找回执，重放时直接返回已提交结果。
        SessionStore.ToolRunGuard guard = sessions.lockToolRun(runId);
        Message existing = sessions.recordedToolResult(runId, assistantSeq, call);
        if (existing != null) return existing;
        if (!guard.active()) throw new IllegalStateException("运行已结束");

        // 工具只接收已绑定归属的入口；执行和追加回执在同一事务内完成。
        Message result;
        AgentTool.Call invocation = new AgentTool.Call(call, bindMemory(guard.agentKey(), guard.pending(), memoryRevision), messages, json);
        try {
            result = tools.prepare(invocation, onStart).get();
        } catch (MemoryWriteDeferred deferred) {
            result = invocation.failed(Map.of(CODE, DEFERRED, MESSAGE, "有新补充，取件后再处理记忆。"));
        }
        sessions.append(runId, result);
        return result;
    }

    private MemoryTool.Store bindMemory(String owner, boolean pending, long memoryRevision) {
        return new MemoryTool.Store() {
            @Override public long requestRevision() { return memoryRevision; }
            @Override public Snapshot read() { return memory.read(owner); }
            @Override public Snapshot readForUpdate() {
                if (pending) throw new MemoryWriteDeferred();
                return memory.locked(owner);
            }
            @Override public boolean update(long revision, String content) {
                return memory.update(owner, revision, content);
            }
        };
    }

    /** 等待补充消息并入上下文后，再由模型决定是否写入。 */
    private static final class MemoryWriteDeferred extends RuntimeException {}
}
