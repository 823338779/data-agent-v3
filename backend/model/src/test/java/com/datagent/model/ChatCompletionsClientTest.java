package com.datagent.model;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.Duration;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatCompletionsClientTest {
    private HttpServer server;
    private final ModelClient.ModelRequest request = new ModelClient.ModelRequest("model", "system", List.of(Message.user("hi")), List.of());

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private ChatCompletionsClient endpoint(String body, int delayMs, Duration timeout) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                if (delayMs > 0) Thread.sleep(delayMs);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.start();
        return new ChatCompletionsClient(new JsonMapper(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "", timeout);
    }

    @Test void assemblesInterleavedCallsAndText() throws Exception {
        String stream = """
                data: {"choices":[{"delta":{"content":"你好","tool_calls":[{"index":1,"id":"b","function":{"name":"second","arguments":"{"}},{"index":0,"id":"a","function":{"name":"first","arguments":"{"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}},{"index":1,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        var chunks = new ArrayList<String>();
        var reply = endpoint(stream, 0, Duration.ofSeconds(3)).complete(request, chunks::add);
        assertThat(chunks).containsExactly("你好");
        assertThat(reply.calls()).containsExactly(new Message.ToolCall("a", "first", "{}"), new Message.ToolCall("b", "second", "{}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}\n\n",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n"})
    void rejectsIncompleteOrTruncatedResponse(String stream) throws Exception {
        var client = endpoint(stream, 0, Duration.ofSeconds(2));
        assertThatThrownBy(() -> client.complete(request, ignored -> {})).isInstanceOf(IllegalStateException.class);
    }

    @Test void readsActualUsageOnceEvenWhenTheAnswerIsRejected() throws Exception {
        String stream = """
                data: {"choices":[{"delta":{"content":"x"},"finish_reason":"length"}],"usage":null}

                data: {"choices":[],"usage":{"prompt_tokens":37,"completion_tokens":0}}

                data: [DONE]

                """;
        // 截断的回答仍可能产生真实费用；usage 尾帧没有 choices。
        var samples = new ArrayList<List<Long>>();
        var measured = request.withUsageListener((input, output) -> samples.add(List.of(input, output)));
        var client = endpoint(stream, 0, Duration.ofSeconds(3));
        assertThatThrownBy(() -> client.complete(measured, ignored -> {})).hasMessageContaining("截断");
        assertThat(samples).containsExactly(List.of(37L, 0L));
    }

    @Test void requestsUsageWithTheStreamingPayload() {
        assertThat(ChatCompletionsClient.wireRequest(request))
                .containsEntry("stream_options", Map.of("include_usage", true));
    }

    @Test void deadlineIncludesReadingBody() throws Exception {
        var client = endpoint("", 1200, Duration.ofMillis(200));
        assertThatThrownBy(() -> client.complete(request, ignored -> {})).hasMessageContaining("超时");
    }

    @Test void stopWithToolCallsIsNotAnExecutableResponse() throws Exception {
        String stream = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"current_time","arguments":"{}"}}]},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        var client = endpoint(stream, 0, Duration.ofSeconds(2));
        assertThatThrownBy(() -> client.complete(request, ignored -> {})).isInstanceOf(IllegalStateException.class);
    }
}
