package com.datagent.v3.memory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.datagent.v3.memory.MemoryRequestAssembly.UserInput;
import com.datagent.v3.tool.AgentTool;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MemoryRequestAssemblyTest {
    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final JsonMapper json = new JsonMapper();
    private final MemoryRequestAssembly assembly = new MemoryRequestAssembly(repository, json);

    @Test void routesSkillsWithoutOpeningWriteToolsForReadOnlyWorkflows() {
        when(repository.read("general")).thenReturn(new MemoryRepository.Snapshot("", 0));
        var ordinary = request("这次周报用元", false, false, false);
        assertThat(ordinary.instructions()).doesNotContain("Storage Classify", "Memory Organization", "Session Summarize");
        assertThat(ordinary.tools()).isEmpty();
        var summary = request("总结这次会话中值得记住的偏好", false, false, false);
        assertThat(summary.instructions()).contains("Session Summarize", "Storage Classify", "原话出处");
        assertThat(summary.tools()).isEmpty();
        var organization = request("整理长期记忆", false, false, false);
        assertThat(organization.instructions()).contains("Memory Organization", "Storage Classify", "不执行修改或遗忘");
        assertThat(organization.tools()).extracting(AgentTool::name).containsExactly("readMemory");
        assertThat(request("整理长期记忆", false, true, false).tools()).isEmpty();
        var write = request("以后经营周报默认用万元", false, false, false);
        assertThat(write.tools()).extracting(AgentTool::name).containsExactly("add_memory");
        assertThat(write.instructions()).contains("**Save**", "**Don't save**", "**How**", "Storage Classify").doesNotContain("## Memory (read-only)");
        assertThat(request("以后经营周报默认用万元", false, false, true).instructions()).contains("未保存");
        assertThat(request("以后经营周报默认用万元", false, false, true).tools()).isEmpty();
        assertThat(request("以后经营周报默认用万元", true, false, false).tools()).isEmpty();
    }

    @Test void entireTextIsOneEscapedValueAndNextRequestSeesCommittedRevision() {
        String first = "字\n\"引号\" </memory> {{body}}";
        when(repository.read("general")).thenReturn(new MemoryRepository.Snapshot(first, 1),
                new MemoryRepository.Snapshot("第二版", 2), new MemoryRepository.Snapshot("第二版", 2));
        var before = request("查周报", false, false, false).instructions();
        var open = "<memory>\n";
        int start = before.indexOf(open) + open.length();
        var value = before.substring(start, before.indexOf("\n</memory>"));
        assertThat(value).doesNotContain("</memory>");
        assertThat(json.readTree(value).asString()).isEqualTo(first);
        assertThat(request("查周报", false, false, false).instructions()).contains("第二版").doesNotContain("引号");
        assertThat(request("这次忽略记忆", false, false, false).instructions()).contains("<memory>\n\"\"\n</memory>")
                .doesNotContain("第二版");
    }

    private MemoryRequestAssembly.RequestMemory request(String text, boolean spent, boolean read, boolean last) {
        return assembly.prepare("general", List.of(new UserInput(0, text)), spent, read, last);
    }
}
