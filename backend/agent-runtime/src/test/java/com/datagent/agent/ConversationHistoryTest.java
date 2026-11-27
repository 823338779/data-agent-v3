package com.datagent.agent;

import com.datagent.model.Message;
import com.datagent.tool.AgentTool;
import com.datagent.tool.CurrentTimeTool;
import com.datagent.tool.MemoryTool;
import com.datagent.tool.ToolRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class ConversationHistoryTest {
    private ConversationContextService contexts(AgentTool... tools) {
        var json = new ObjectMapper();
        return new ConversationContextService(json, new ToolRegistry(List.of(tools), json), 32768, 2048, 768);
    }

    @Test void retainsHistoricalMemoryCallsAndReceiptsInOriginalOrder() {
        var user = Message.user("更新偏好");
        var read = new Message.ToolCall("x", MemoryTool.NAME, "{\"action\":\"read\"}");
        var edit = new Message.ToolCall("m", MemoryTool.NAME, "{\"action\":\"edit\",\"old_string\":\"私有旧行\"}");
        var legacy = new Message.ToolCall("l", "edit_memory", "{\"old_string\":\"私有旧行\"}");
        var legacyResult = Message.result(legacy, "旧工具回执", true);
        var time = new Message.ToolCall("t", CurrentTimeTool.NAME, "{}");
        // 同一批混合工具使用各自回执；下一批非记忆工具还可复用之前的 ID。
        var reused = new Message.ToolCall("m", CurrentTimeTool.NAME, "{}");
        var timeResult = Message.result(time, "时间", true);
        var later = Message.assistant("", List.of(reused));
        var laterResult = Message.result(reused, "另一次查询", true);
        var source = List.of(user, Message.assistant("", List.of(read)), Message.result(read, "私有原文", true),
                Message.assistant("查询中", List.of(time, edit, legacy)), Message.result(edit, "写入失败", false),
                timeResult, legacyResult, later, laterResult, Message.assistant("完成", List.of(read)), Message.result(read, "私有原文", true));

        var service = contexts(new MemoryTool(), new CurrentTimeTool(Clock.systemUTC()));
        assertThat(service.historyMessages(source)).containsExactlyElementsOf(source);
        assertThat(source.get(1).calls()).containsExactly(read);
    }

    @Test void anotherToolCanExcludeItsHistoryByOverridingOnlyTheOption() {
        var time = new Message.ToolCall("t", CurrentTimeTool.NAME, "{}");
        var messages = List.of(Message.assistant("", List.of(time)), Message.result(time, "时间", true));
        var optedOut = new CurrentTimeTool(Clock.systemUTC()) {
            @Override public boolean includeResultInHistory() { return false; }
        };
        // 同一段历史只随工具的声明改变，原始调用和回执可以再次用于回放。
        assertThat(contexts(optedOut).historyMessages(messages)).isEmpty();
        assertThat(contexts(new CurrentTimeTool(Clock.systemUTC())).historyMessages(messages))
                .containsExactlyElementsOf(messages);
    }
}
