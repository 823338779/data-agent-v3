package com.datagent.memory;

import com.datagent.tool.AgentTool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import com.datagent.memory.MemoryIntent.Kind;

/** 组装本次模型请求的同一份快照、模板与有限工具集。 */
@Component
public class MemoryRequestAssembly {
    private static final String TEMPLATE = "prompts/memory-context.md";
    private static final String SKILL_FORMAT = "skills/%s/SKILL.md";
    private static final String WRITABLE = "<!-- writable -->", READ_ONLY = "<!-- readonly -->", COMMON = "<!-- common -->";
    private static final String PLACEHOLDER = "{{body}}";
    private static final String EXTRACT = "session-summarize", CLASSIFY = "storage-classify", ORGANIZE = "memory-organization";
    private static final String LINE_BREAK = "\n";
    private static final String SEPARATOR = "\n\n";
    private static final AgentTool READ = new MemoryTool(MemoryPolicy.READ,
            "读取当前 Agent 已提交的整份长期记忆，无需参数，不会修改。", Map.of());
    private static final AgentTool ADD = new MemoryTool(MemoryPolicy.ADD,
            "追加这次用户明确授权的一条完整偏好。", Map.of(MemoryPolicy.CONTENT, "完整原话"));
    private static final AgentTool EDIT = new MemoryTool(MemoryPolicy.EDIT,
            "替换唯一匹配的完整旧行，明确要求遗忘时新值为空。", Map.of(
                    MemoryPolicy.OLD, "完整旧行", MemoryPolicy.NEW, "完整新行，明确遗忘时为空"));
    private static final String FORBIDDEN = "本次无法确定唯一的记忆操作。请用户明确指定完整偏好或要改删的原文，不写入。";
    private static final String LAST = "本轮已到最后一次模型请求。若用户还有待执行的记忆变更，说明未保存，请在新一轮重发。";
    private static final String AUTHORIZED_ARGUMENTS = "后端允许的精确工具参数：";
    private static final String RECEIPT_REQUIRED = "本次若要修改记忆，请明确调用已挂载的工具；没有成功回执就不得声称已修改。";
    /** PostgreSQL 快照与外部数据转义。 */
    private final MemoryRepository repository;
    private final ObjectMapper json;
    /** 只读和可写片段与静态 Skill，独立于动态数据。 */
    private final String writable, readonly, common;
    private final Map<String, String> skills;

    MemoryRequestAssembly(MemoryRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
        var template = load(TEMPLATE);
        int readAt = template.indexOf(READ_ONLY);
        int commonAt = template.indexOf(COMMON);
        writable = template.substring(WRITABLE.length(), readAt).strip();
        readonly = template.substring(readAt + READ_ONLY.length(), commonAt).strip();
        common = template.substring(commonAt + COMMON.length()).strip();
        skills = Map.of(EXTRACT, load(SKILL_FORMAT.formatted(EXTRACT)), CLASSIFY, load(SKILL_FORMAT.formatted(CLASSIFY)),
                ORGANIZE, load(SKILL_FORMAT.formatted(ORGANIZE)));
    }

    public record UserInput(int messageSeq, String rawText) {}
    public record RequestMemory(String instructions, List<AgentTool> tools, MemoryPolicy.Grant write, boolean canRead, boolean ignored) {}

    public RequestMemory prepare(String owner, List<UserInput> input, boolean spent, boolean read, boolean last) {
        // 只用当前已提交的一个正文版本来决定写资格与本次模型视图。
        MemoryRepository.Snapshot snapshot;
        try {
            snapshot = repository.read(owner);
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new IllegalStateException("长期记忆读取失败，本轮未能核实记忆。", unavailable);
        }
        var decision = MemoryPolicy.authorize(input, snapshot, spent);
        var grant = last ? null : decision.grant();
        var kind = decision.kind();
        boolean canRead = !last && !read && (kind == Kind.VIEW || kind == Kind.ORGANIZE);
        List<AgentTool> tools = grant != null ? List.of(MemoryPolicy.ADD.equals(grant.tool()) ? ADD : EDIT)
                : canRead ? List.of(READ) : List.of();
        String body = kind == Kind.IGNORE ? "" : snapshot.content();
        String encoded = encode(body);
        var prompt = new StringBuilder(grant == null ? readonly : writable).append(SEPARATOR)
                .append(common.replace(PLACEHOLDER, encoded));
        switch (kind) {
            case EXTRACT -> appendSkills(prompt, EXTRACT, CLASSIFY);
            case ORGANIZE -> appendSkills(prompt, ORGANIZE, CLASSIFY);
            case SAVE, EDIT, FORGET -> appendSkills(prompt, CLASSIFY);
            case CLARIFY -> prompt.append(SEPARATOR).append(FORBIDDEN);
            default -> { }
        }
        if (grant != null) prompt.append(SEPARATOR).append(RECEIPT_REQUIRED).append(SEPARATOR)
                .append(AUTHORIZED_ARGUMENTS).append(encode(grant.arguments()));
        if (last && decision.grant() != null) prompt.append(SEPARATOR).append(LAST);
        return new RequestMemory(prompt.toString(), tools, grant, canRead, kind == Kind.IGNORE);
    }

    private String encode(Object value) {
        return json.writeValueAsString(value).replace("<", "\\u003c").replace(">", "\\u003e");
    }

    private void appendSkills(StringBuilder prompt, String... names) {
        for (var name : names) prompt.append(SEPARATOR).append(skills.get(name));
    }

    private static String load(String path) {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            // 模型提示词统一使用 LF，避免检出环境改变资源中的协议分隔符。
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .collect(java.util.stream.Collectors.joining(LINE_BREAK)).strip();
        } catch (IOException unavailable) {
            throw new IllegalStateException("记忆提示词资源无法读取", unavailable);
        }
    }
}
