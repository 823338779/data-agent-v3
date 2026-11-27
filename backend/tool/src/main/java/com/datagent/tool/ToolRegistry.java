package com.datagent.tool;

import java.util.*;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import com.datagent.model.Message;
import com.datagent.model.Message.ToolCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ToolRegistry {
    private static final String FUNCTION = "function";
    private static final String NAME = "name";
    private static final String PARAMETERS = "parameters", STRICT = "strict";
    private static final String CODE = "code", NOT_AUTHORIZED = "NOT_AUTHORIZED";
    /** 按工具名查找实现，遍历顺序与发布给模型的顺序一致。 */
    private final Map<String, AgentTool> tools;
    /** 解析工具参数并序列化模型回执。 */
    private final ObjectMapper json;

    public ToolRegistry(List<AgentTool> tools, ObjectMapper json) {
        // 同名工具无法按调用名唯一分派，在启动时拒绝。
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            String name = tool.name();
            if (byName.putIfAbsent(name, tool) != null) throw new IllegalArgumentException("工具名重复：" + name);
        }
        this.tools = Collections.unmodifiableMap(byName);
        this.json = json;
    }

    /** 将工具的描述和参数转换为模型协议。 */
    private static Map<String, Object> schema(AgentTool tool) {
        return Map.of(AgentTool.TYPE, FUNCTION, NAME, tool.name(),
                AgentTool.DESCRIPTION, tool.description(), PARAMETERS, tool.schema(), STRICT, false);
    }

    /** 按注册顺序发布全部工具定义。 */
    public List<Map<String, Object>> definitions() {
        return tools.values().stream().map(ToolRegistry::schema).toList();
    }

    /** 按模型调用名查找工具；未注册时返回 null。 */
    public AgentTool find(String name) {
        return tools.get(name);
    }

    /** 只展示成功回执声明的摘要，保留其他字段在工具消息中。 */
    public String resultSummary(Message result) {
        if (!Boolean.TRUE.equals(result.success())) return "";
        var summary = json.readTree(result.content()).path(AgentTool.SUMMARY);
        return summary.isString() ? summary.asString() : "";
    }

    public Message invoke(ToolCall call, Runnable onStart) {
        return invoke(call, AgentTool.Context.EMPTY, List.of(), onStart);
    }

    /** 直接调用工具的入口，执行异常作为回执交还调用方。 */
    public Message invoke(ToolCall call, AgentTool.Context context, List<Message> messages, Runnable onStart) {
        AgentTool.Call invocation = new AgentTool.Call(call, context, messages, json);
        Supplier<Message> operation = prepare(invocation, onStart);
        try {
            return operation.get();
        } catch (AgentTool.InputError invalid) {
            return invocation.failed(invalid.getMessage());
        } catch (RuntimeException failure) {
            return invocation.failed("工具执行失败");
        }
    }

    /** 校验参数并返回待执行操作，运行时可以在事务内执行该操作。 */
    public Supplier<Message> prepare(ToolCall call, AgentTool.Context context, List<Message> messages, Runnable onStart) {
        return prepare(new AgentTool.Call(call, context, messages, json), onStart);
    }

    /** 参数校验与工具实现使用同一次调用生成回执。 */
    public Supplier<Message> prepare(AgentTool.Call call, Runnable onStart) {
        AgentTool tool = find(call.request().name());
        if (tool == null) return () -> call.failed(Map.of(CODE, NOT_AUTHORIZED, AgentTool.ERROR, "工具未注册"));

        Supplier<Message> operation;
        try {
            JsonNode args = json.readTree(call.request().arguments());
            Set<String> fields = tool.parameters().keySet();
            if (args.isObject()) AgentTool.requireParameters(args.propertyNames(), tool.requiredParameters());
            if (!args.isObject() || !fields.containsAll(args.propertyNames())
                    || args.propertyNames().stream().anyMatch(field -> AgentTool.INTEGER.equals(tool.parameterType(field))
                            ? !args.get(field).isIntegralNumber() : !args.get(field).isString())) {
                return () -> call.failed("工具参数与定义不符");
            }
            operation = tool.prepare(call, args);
        } catch (SecurityException denied) {
            // 工具在准备操作时校验当前调用的权限。
            return () -> call.failed(Map.of(CODE, NOT_AUTHORIZED, AgentTool.ERROR, "本次请求未授权该操作"));
        } catch (AgentTool.InputError invalid) {
            return () -> call.failed(invalid.getMessage());
        } catch (RuntimeException invalid) {
            return () -> call.failed("无效的工具参数，请检查格式和取值");
        }
        return () -> {
            onStart.run();
            return operation.get();
        };
    }

}
