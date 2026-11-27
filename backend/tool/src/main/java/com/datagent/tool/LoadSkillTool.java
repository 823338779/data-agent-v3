package com.datagent.tool;

import com.datagent.memory.SkillCatalog;
import com.datagent.model.Message;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 让模型按目录中的名称读取技能正文。 */
@Component
@RequiredArgsConstructor
public class LoadSkillTool implements AgentTool {
    public static final String NAME = "load_skill";
    private static final String SKILL_NAME = "skill_name", CONTENT = "content";
    private static final String HELP = "按名称加载技能步骤。整理长期记忆时调用本工具，参数示例：{\"skill_name\":\"memory-organization\"}。";
    private static final Map<String, String> PARAMETERS = Map.of(SKILL_NAME, "available_skills 中的技能名。");
    /** 与系统提示词共享的内置技能目录。 */
    private final SkillCatalog skills;

    @Override public String name() { return NAME; }
    @Override public String description() { return HELP; }
    @Override public Map<String, String> parameters() { return PARAMETERS; }

    @Override public Supplier<Message> prepare(Call call, JsonNode arguments) {
        // 未收录的名称作为可纠正错误交回模型。
        String name = arguments.get(SKILL_NAME).asString();
        SkillCatalog.Skill skill = skills.find(name);
        if (skill == null) throw new InputError("未知技能：" + name + "。请从 available_skills 中选择 skill_name。");
        // 仅在执行 load_skill 时读取指定正文，读取失败也要向本轮模型返回回执。
        return () -> {
            try {
                String body = skill.body();
                if (body.isEmpty()) return call.failed("技能正文为空：" + name);
                return call.success(Map.of(SKILL_NAME, name, CONTENT, body));
            } catch (IOException | UncheckedIOException unavailable) {
                return call.failed("无法读取技能正文：" + name);
            }
        };
    }
}
