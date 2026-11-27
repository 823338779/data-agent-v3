package com.datagent.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/** Agent 的标识、系统指令和模型名。 */
public record AgentDefinition(String key, String instructions, String model) {
    public static final String GENERAL = "general";
    /** 主对话的角色、任务和工具调用规则。 */
    private static final String TEMPLATE = "prompts/general-system.md";

    public static AgentDefinition general(String modelName) {
        // Agent 初始化时加载固定指令，每次请求再附上技能目录和记忆快照。
        try {
            String instructions = new ClassPathResource(TEMPLATE).getContentAsString(StandardCharsets.UTF_8);
            return new AgentDefinition(GENERAL, instructions.replace("\r\n", "\n").strip(), modelName);
        } catch (IOException unavailable) {
            throw new IllegalStateException("通用系统提示词无法读取", unavailable);
        }
    }
}
