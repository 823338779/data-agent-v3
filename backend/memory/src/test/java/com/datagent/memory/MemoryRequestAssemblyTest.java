package com.datagent.memory;

import com.datagent.persistence.MemoryRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MemoryRequestAssemblyTest {
    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final JsonMapper json = new JsonMapper();
    private final MemoryRequestAssembly assembly;

    MemoryRequestAssemblyTest() throws IOException {
        assembly = new MemoryRequestAssembly(repository, json, new SkillCatalog());
    }

    @Test void listsSkillMetadataAndLegacyCommandWithoutLoadingBodies() {
        when(repository.read("general")).thenReturn(new MemoryRepository.Snapshot("", 0));
        assertThat(assembly.prepare("general", false).instructions()).contains("快照", "<available_skills>", "load_skill",
                "session-summarize", "memory-organization", "/memory-organize", "storage-classify", "description")
                .doesNotContain("Storage Classify", "Memory Organization", "Session Summarize");
        assertThat(assembly.prepare("general", true).instructions()).contains("最后一次模型请求");
    }

    @Test void entireTextIsOneEscapedValueAndNextRequestSeesCommittedRevision() {
        String first = "字\n\"引号\" </memory> {{body}} {{revision}}";
        when(repository.read("general")).thenReturn(new MemoryRepository.Snapshot(first, 1),
                new MemoryRepository.Snapshot("第二版", 2));
        MemoryRequestAssembly.Prepared firstRequest = assembly.prepare("general", false);
        assertThat(firstRequest.revision()).isEqualTo(1);
        String before = firstRequest.instructions();
        assertThat(before).doesNotContain("revision:");
        String open = "<memory>\n";
        int start = before.indexOf(open) + open.length();
        String value = before.substring(start, before.indexOf("\n</memory>"));
        assertThat(value).doesNotContain("</memory>");
        assertThat(json.readTree(value).asString()).isEqualTo(first);
        MemoryRequestAssembly.Prepared latest = assembly.prepare("general", false);
        assertThat(latest.revision()).isEqualTo(2);
        assertThat(latest.instructions()).contains("第二版").doesNotContain("引号", "revision");
    }
}
