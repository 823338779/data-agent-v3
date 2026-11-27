package com.datagent.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 从内置 SKILL.md 建立名称索引，目录和工具回执使用同一份资源。 */
@Component
public class SkillCatalog {
    private static final String RESOURCES = "classpath*:skills/*/SKILL.md";
    private static final String FENCE = "---", LF = "\n";
    private static final String NAME = "name", DESCRIPTION = "description", TAGS = "tags";
    /** 系统目录仅展示名称、用途和可选标签；资源引用供工具按需读取正文。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Skill(String name, String description, List<String> tags, @JsonIgnore Resource resource) {
        public String body() throws IOException {
            try (BufferedReader reader = open(resource)) {
                readHeader(reader, resource);
                return reader.lines().collect(Collectors.joining(LF)).strip();
            }
        }
    }
    /** 按名称排序的内置技能索引。 */
    private final Map<String, Skill> skills;

    public SkillCatalog() throws IOException {
        Map<String, Skill> byName = new TreeMap<>();
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        // 仅扫描应用资源；模型输入始终作为索引键使用。
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(RESOURCES)) {
            Object header;
            try (BufferedReader reader = open(resource)) {
                header = yaml.load(readHeader(reader, resource));
            }
            if (!(header instanceof Map<?, ?> fields) || !(fields.get(NAME) instanceof String name) || name.isBlank()
                    || !(fields.get(DESCRIPTION) instanceof String description) || description.isBlank()) {
                throw new IllegalStateException("技能缺少名称或描述：" + resource);
            }
            List<String> tags = fields.get(TAGS) instanceof List<?> values ? values.stream().map(String.class::cast).toList() : List.of();
            if (byName.putIfAbsent(name, new Skill(name, description, tags, resource)) != null) {
                throw new IllegalStateException("技能名称重复：" + name);
            }
        }
        skills = Collections.unmodifiableMap(byName);
    }

    public Collection<Skill> available() { return skills.values(); }

    public Skill find(String name) { return skills.get(name); }

    private static BufferedReader open(Resource resource) throws IOException {
        return new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
    }

    /** 读到 YAML 结束标记为止，让调用方可以从同一个流继续读取正文。 */
    private static String readHeader(BufferedReader reader, Resource resource) throws IOException {
        if (!FENCE.equals(reader.readLine())) throw new IOException("技能缺少元数据：" + resource);
        StringBuilder header = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (FENCE.equals(line)) return header.toString();
            header.append(line).append(LF);
        }
        throw new IOException("技能元数据未闭合：" + resource);
    }
}
