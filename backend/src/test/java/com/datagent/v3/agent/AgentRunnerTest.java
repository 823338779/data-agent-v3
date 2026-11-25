package com.datagent.v3.agent;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.datagent.v3.session.*;
import com.datagent.v3.tool.*;
import com.datagent.v3.model.*;
import com.datagent.v3.model.ModelClient.ModelReply;

@SpringBootTest
class AgentRunnerTest extends PostgresTest {
    @Autowired SessionStore store;
    @Autowired AgentDefinition agent;
    @Autowired ToolRegistry tools;

    @Test void multipleCallsAndSelfCorrectionKeepOriginalIds() {
        var step = new AtomicInteger();
        List<List<Message>> inputs = new ArrayList<>();
        ModelClient model = (request, onText) -> {
            inputs.add(List.copyOf(request.messages()));
            return switch (step.incrementAndGet()) {
                case 1 -> new ModelReply("", List.of(new Message.ToolCall("1", "current_time", "{}"),
                        new Message.ToolCall("2", "convert_timezone", "{}")));
                case 2 -> new ModelReply("", List.of(new Message.ToolCall("3", "convert_timezone",
                        "{\"timestamp\":\"2026-11-26T01:00:00Z\",\"timezone\":\"Asia/Tokyo\"}")));
                default -> { onText.accept("东京 10 点"); yield new ModelReply("东京 10 点", List.of()); }
            };
        };
        var session = store.createSession(agent.key());
        var runner = new AgentRunner(store, tools, agent, model);
        var events = new ArrayList<RunEvent>();
        runner.execute(store.beginRun(session, "东京"), events::add);
        assertThat(inputs.get(1)).extracting(Message::toolCallId).containsSequence("1", "2");
        assertThat(inputs.get(1).getLast().success()).isFalse();
        assertThat(inputs.get(2).getLast().content()).contains("10:00:00+09:00");
        assertThat(events).extracting(RunEvent::type).containsExactly("run.started", "tool.started", "tool.finished",
                "tool.finished", "tool.started", "tool.finished", "text.delta", "run.completed");
        assertThat(events.getLast().messageSeq()).isEqualTo(6);
        runner.execute(store.beginRun(session, "刚才"), events::add);
        assertThat(inputs.getLast()).extracting(Message::content).contains("东京 10 点", "刚才");
    }

    @Test void stopsOnEighthRequest() {
        var session = store.createSession(agent.key());
        var count = new AtomicInteger();
        var runner = new AgentRunner(store, tools, agent, (request, delta) ->
                new ModelReply("", List.of(new Message.ToolCall("t" + count.incrementAndGet(), "current_time", "{}"))));
        var events = new ArrayList<RunEvent>();
        runner.execute(store.beginRun(session, "loop"), events::add);
        assertThat(count.get()).isEqualTo(8);
        assertThat(events.stream().filter(e -> "tool.started".equals(e.type())).count()).isEqualTo(7);
        assertThat(events.stream().filter(e -> "run.failed".equals(e.type())).count()).isEqualTo(1);
        assertThat(store.snapshot(session).activeRunId()).isNull();
    }

    @Test void interruptedStreamPreservesPartialTextAndReleasesSession() {
        var session = store.createSession(agent.key());
        var runner = new AgentRunner(store, tools, agent, (request, delta) -> {
            delta.accept("未完成");
            throw new IllegalStateException("连接中断");
        });
        var events = new ArrayList<RunEvent>();
        runner.execute(store.beginRun(session, "hi"), events::add);
        assertThat(events.getLast().type()).isEqualTo("run.failed");
        assertThat(store.snapshot(session).runs().getLast().messages().getLast().content()).isEqualTo("未完成");
        new AgentRunner(store, tools, agent, (request, delta) -> new ModelReply(" ", List.of()))
                .execute(store.beginRun(session, "empty"), events::add);
        assertThat(store.snapshot(session).runs().getLast().status()).isEqualTo("FAILED");
    }
}
