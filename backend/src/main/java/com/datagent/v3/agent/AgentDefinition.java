package com.datagent.v3.agent;

import java.util.List;
import com.datagent.v3.tool.CurrentTimeTool;
import com.datagent.v3.tool.ConvertTimezoneTool;

/** 标识、系统指令、模型名和有序工具允许列表组成静态 Agent 定义。 */
public record AgentDefinition(String key, String instructions, String model, List<String> tools) {
    public static final String GENERAL = "general";
    private static final String INSTRUCTIONS = "你是通用助手。按需使用提供的工具。当前时间需要查询，"
            + "转换时区时使用时区换算工具。追问引用先前的时间时使用历史中的原始时间。工具不可用时如实说明。";

    public AgentDefinition {
        tools = List.copyOf(tools);
    }

    public static AgentDefinition general(String modelName) {
        return new AgentDefinition(GENERAL, INSTRUCTIONS, modelName, List.of(CurrentTimeTool.NAME, ConvertTimezoneTool.NAME));
    }
}
