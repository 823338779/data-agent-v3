package com.datagent.memory;

import com.datagent.persistence.MemoryRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 组装本次模型请求的记忆快照和提示词。 */
@Component
public class MemoryRequestAssembly {
    private static final String MEMORY_TEMPLATE = "prompts/memory-context.md";
    /** 技能目录在启动时生成，独立于每次请求读取的记忆。 */
    private static final String SKILLS_TEMPLATE = "prompts/available-skills.md";
    private static final String SKILLS_PLACEHOLDER = "{{skills}}";
    private static final String PLACEHOLDER = "{{body}}";
    private static final String LINE_BREAK = "\n";
    private static final String SEPARATOR = "\n\n";
    private static final String LAST = "这是本轮最后一次模型请求，请直接回答。若记忆变更尚未成功，说明未保存。";
    /** PostgreSQL 快照与外部数据转义。 */
    private final MemoryRepository repository;
    private final ObjectMapper json;
    /** 已渲染的技能目录和待填入快照的记忆模板。 */
    private final String skillDirectory;
    private final String memoryTemplate;

    MemoryRequestAssembly(MemoryRepository repository, ObjectMapper json, SkillCatalog skills) {
        this.repository = repository;
        this.json = json;
        // 所有请求共用目录，技能正文按需从工具回执进入对话。
        skillDirectory = load(SKILLS_TEMPLATE).replace(SKILLS_PLACEHOLDER, encode(skills.available()));
        memoryTemplate = load(MEMORY_TEMPLATE);
    }

    /** instructions 发送给模型，revision 仅供后端核对编辑所依据的快照。 */
    public record Prepared(String instructions, long revision) {}

    /** 每次业务请求读取当前快照，由模型结合用户消息决定如何使用。 */
    public Prepared prepare(String owner, boolean last) {
        MemoryRepository.Snapshot snapshot;
        try {
            snapshot = repository.read(owner);
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new IllegalStateException("长期记忆读取失败，本轮未能核实记忆。", unavailable);
        }
        String prompt = skillDirectory + SEPARATOR + memoryTemplate.replace(PLACEHOLDER, encode(snapshot.content()));
        return new Prepared(last ? prompt + SEPARATOR + LAST : prompt, snapshot.revision());
    }

    private String encode(Object value) {
        return json.writeValueAsString(value).replace("<", "\\u003c").replace(">", "\\u003e");
    }

    private static String load(String path) {
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            // 模型提示词统一使用 LF，避免检出环境改变资源中的协议分隔符。
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .collect(java.util.stream.Collectors.joining(LINE_BREAK)).strip();
        } catch (IOException unavailable) {
            throw new IllegalStateException("记忆提示词资源无法读取", unavailable);
        }
    }
}
