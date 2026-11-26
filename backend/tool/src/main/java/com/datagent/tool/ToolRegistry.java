package com.datagent.tool;

import java.util.*;
import java.util.function.Supplier;
import com.datagent.model.Message;
import com.datagent.model.Message.ToolCall;
import tools.jackson.databind.ObjectMapper;

public class ToolRegistry {
    private static final String FUNCTION = "function";
    private static final String NAME = "name";
    private static final String PARAMETERS = "parameters";
    private static final String ERROR = "error";
    /** 所有已注册工具，按模型调用名索引。 */
    private final Map<String, AgentTool> tools = new HashMap<>();
    /** 负责解析参数与序列化工具结果。 */
    private final ObjectMapper json;

    public ToolRegistry(List<AgentTool> implementations, List<String> allowedTools, ObjectMapper json) {
        this.json = json;
        for (var tool : implementations) {
            if (tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("工具名重复：" + tool.name());
            }
        }
        if (!tools.keySet().containsAll(allowedTools)) {
            throw new IllegalArgumentException("Agent 引用了未注册工具");
        }
    }

    public List<Map<String, Object>> visibleTools(List<String> allowedTools) {
        return visibleTools(allowedTools, null);
    }

    public List<Map<String, Object>> visibleTools(List<String> allowedTools, AgentTool scoped) {
        return visibleScopedTools(allowedTools, scoped == null ? List.of() : List.of(scoped));
    }

    public List<Map<String, Object>> visibleScopedTools(List<String> allowedTools, List<AgentTool> scoped) {
        var available = new ArrayList<>(allowedTools.stream().map(tools::get).toList());
        var names = new HashSet<>(allowedTools);
        for (var tool : scoped) {
            if (!names.add(tool.name())) throw new IllegalArgumentException("工具名重复：" + tool.name());
            available.add(tool);
        }
        return available.stream().map(tool -> Map.<String, Object>of(AgentTool.TYPE, FUNCTION,
                FUNCTION, Map.of(NAME, tool.name(), AgentTool.DESCRIPTION, tool.description(), PARAMETERS, tool.schema()))).toList();
    }

    public Message invoke(List<String> allowedTools, ToolCall call, Runnable onStart) {
        return invoke(allowedTools, call, null, onStart);
    }

    public Message invoke(List<String> allowedTools, ToolCall call, AgentTool scoped, Runnable onStart) {
        return invokeScoped(allowedTools, call, scoped == null ? List.of() : List.of(scoped), onStart);
    }

    public Message invokeScoped(List<String> allowedTools, ToolCall call, List<AgentTool> scoped, Runnable onStart) {
        // 运行内能力由服务端绑定；普通工具仍按 Agent 可见范围校验。
        AgentTool tool = scoped.stream().filter(t -> t.name().equals(call.name())).findFirst()
                .orElseGet(() -> allowedTools.contains(call.name()) ? tools.get(call.name()) : null);
        if (tool == null) {
            return error(call, "工具未开放");
        }
        Supplier<Map<String, String>> operation;
        try {
            var args = json.readTree(call.arguments());
            var fields = tool.parameters().keySet();
            if (!args.isObject() || !fields.containsAll(args.propertyNames())
                    || !args.propertyNames().containsAll(tool.requiredParameters())
                    || args.propertyNames().stream().anyMatch(field -> AgentTool.INTEGER.equals(tool.parameterType(field))
                            ? !args.get(field).isIntegralNumber() : !args.get(field).isString())) {
                return error(call, "工具参数与定义不符");
            }
            operation = tool.prepare(args);
        } catch (AgentTool.InputError invalid) {
            return error(call, invalid.getMessage());
        } catch (RuntimeException invalid) {
            return error(call, "无效的工具参数，请检查格式和取值");
        }
        // 执行错误作为带原调用 ID 的结果交还模型。
        onStart.run();
        try {
            return Message.result(call, json.writeValueAsString(operation.get()), true);
        } catch (AgentTool.InputError invalid) {
            return error(call, invalid.getMessage());
        } catch (RuntimeException failure) {
            return error(call, "工具执行失败");
        }
    }

    private Message error(ToolCall call, String reason) {
        return Message.result(call, json.writeValueAsString(Map.of(ERROR, reason)), false);
    }
}
