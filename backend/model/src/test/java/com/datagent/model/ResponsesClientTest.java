package com.datagent.model;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ResponsesClientTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String DELTA = "response.output_text.delta", COMPLETED = "response.completed";
    private final ModelClient.ModelRequest request = new ModelClient.ModelRequest("model", "system", List.of(Message.user("hi")), List.of());
    private final AtomicReference<JsonNode> sent = new AtomicReference<>();
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private ResponsesClient endpoint(String body, int delayMs, Duration timeout) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            try (exchange) {
                sent.set(JSON.readTree(exchange.getRequestBody()));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                if (delayMs > 0) Thread.sleep(delayMs);
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.start();
        return new ResponsesClient(JSON, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"), "", timeout);
    }

    private static String event(String type, Map<String, Object> fields) {
        Map<String, Object> data = new LinkedHashMap<>(fields);
        data.put("type", type);
        return "event: " + type + "\ndata: " + JSON.writeValueAsString(data) + "\n\n";
    }

    private static String completed(Object... output) {
        return event(COMPLETED, Map.of("response", Map.of("status", "completed", "output", List.of(output))));
    }

    private static Map<String, Object> call(String id, String name) {
        return Map.of("type", "function_call", "id", "fc_" + id, "call_id", id, "name", name, "arguments", "{}");
    }

    @Test void assemblesInterleavedCallsAndTextAndReplaysReasoning() throws Exception {
        // 输出参数分片可能交错，终止快照给出完整调用顺序和推理项。
        var reasoning = Map.of("id", "rs_1", "type", "reasoning", "encrypted_content", "opaque",
                "summary", List.of(Map.of("type", "summary_text", "text", "摘要")));
        var message = Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", "你好")));
        String stream = event(DELTA, Map.of("delta", "你"))
                + event("response.function_call_arguments.delta", Map.of("item_id", "fc_b", "delta", "{"))
                + event("response.reasoning_summary_text.delta", Map.of("delta", "摘要"))
                + event("response.function_call_arguments.delta", Map.of("item_id", "fc_a", "delta", "{"))
                + event(DELTA, Map.of("delta", "好"))
                + completed(reasoning, message, call("a", "first"), call("b", "second"));
        ArrayList<String> chunks = new ArrayList<>();
        var reply = endpoint(stream, 0, Duration.ofSeconds(3)).complete(request, chunks::add);
        assertThat(chunks).containsExactly("你", "好");
        assertThat(reply.text()).isEqualTo("你好");
        assertThat(reply.calls()).containsExactly(new Message.ToolCall("a", "first", "{}"), new Message.ToolCall("b", "second", "{}"));
        assertThat(reply.toolReasoning()).containsExactly(JSON.valueToTree(reasoning));
        assertThat(sent.get().path("instructions").asString()).isEqualTo("system");
        assertThat(sent.get().path("store").asBoolean()).isFalse();

        // 回执按 call_id 与独立的函数调用项配对；摘要请求和历史轮次没有推理项。
        List<Message> history = List.of(Message.user("hi"), Message.assistant(reply.text(), reply.calls()),
                Message.result(reply.calls().getFirst(), "ok", true), Message.result(reply.calls().getLast(), "other", false));
        var followup = new ModelClient.ModelRequest("model", "system", history, List.of(), 100, null, Map.of(1, reply.toolReasoning()));
        var wire = JSON.valueToTree(ResponsesClient.wireRequest(followup));
        assertThat(wire.path("max_output_tokens").asInt()).isEqualTo(100);
        assertThat(wire.path("input").get(1)).isEqualTo(JSON.valueToTree(reasoning));
        assertThat(wire.path("input").get(3).path("call_id").asString()).isEqualTo("a");
        assertThat(wire.path("input").get(6)).isEqualTo(JSON.valueToTree(Map.of("type", "function_call_output", "call_id", "b", "output", "other")));
        assertThat(wire.has("previous_response_id")).isFalse();
    }

    @Test void reportsUsageOnceEvenWhenTruncated() throws Exception {
        var usage = Map.of("input_tokens", 37, "output_tokens", 0);
        String stream = event("response.incomplete", Map.of("response", Map.of("status", "incomplete",
                "incomplete_details", Map.of("reason", "max_output_tokens"), "usage", usage)));
        ArrayList<List<Long>> samples = new ArrayList<>();
        var client = endpoint(stream, 0, Duration.ofSeconds(3));
        assertThatThrownBy(() -> client.complete(request.withUsageListener((i, o) -> samples.add(List.of(i, o))), ignored -> {}))
                .hasMessageContaining("截断");
        assertThat(samples).containsExactly(List.of(37L, 0L));
    }

    @Test void treatsOllamaCompletedAtTheTokenLimitAsPotentialTruncation() throws Exception {
        String stream = event(COMPLETED, Map.of("response", Map.of("status", "completed",
                "usage", Map.of("output_tokens", 8), "output", List.of(call("a", "first")))));
        var client = endpoint(stream, 0, Duration.ofSeconds(3));
        var limited = new ModelClient.ModelRequest("model", "system", List.of(Message.user("hi")), List.of(), 8);
        assertThatThrownBy(() -> client.complete(limited, ignored -> {})).hasMessageContaining("截断");
    }

    @Test void reasoningOnlyAndCompletedIsRecoverableWithoutExposingReasoning() throws Exception {
        var client = endpoint(completed(Map.of("type", "reasoning", "encrypted_content", "private-trace")), 0, Duration.ofSeconds(3));
        assertThatThrownBy(() -> client.complete(request, text -> fail("不应推送推理")))
                .isInstanceOf(ModelClient.EmptyResponse.class).hasMessageContaining("仅返回推理").hasMessageNotContaining("private-trace");
    }

    @Test void partialEventOrTransportMarkerCannotCompleteAToolCall() throws Exception {
        String stream = event("response.output_item.done", Map.of("item", call("a", "first")))
                + "data: [DONE]\n\ndata: {\"type\":\"response.completed\"";
        var client = endpoint(stream, 0, Duration.ofSeconds(3));
        assertThatThrownBy(() -> client.complete(request, ignored -> {})).isInstanceOf(ModelClient.InterruptedResponse.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"in_progress", "incomplete"})
    void incompleteToolsCannotBeExecuted(String status) throws Exception {
        Map<String, Object> item = new HashMap<>(call("a", "first"));
        item.put("status", status);
        var client = endpoint(completed(item), 0, Duration.ofSeconds(3));
        assertThatThrownBy(() -> client.complete(request, ignored -> {})).hasMessageContaining("调用不完整");
    }

    @Test void failedTerminalIsNotAnEmptyAnswer() throws Exception {
        String stream = event("response.failed", Map.of("response", Map.of("status", "failed", "error", Map.of("code", "server_error"))));
        var client = endpoint(stream, 0, Duration.ofSeconds(3));
        assertThatThrownBy(() -> client.complete(request, ignored -> {})).hasMessageContaining("server_error")
                .isNotInstanceOf(ModelClient.EmptyResponse.class);
    }

    @Test void deadlineIncludesReadingBody() throws Exception {
        var client = endpoint("", 1200, Duration.ofMillis(200));
        assertThatThrownBy(() -> client.complete(request, ignored -> {})).hasMessageContaining("超时");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitsForResponseBodyEndAfterTerminalEventAndDoneMarker(boolean empty) throws Exception {
        CountDownLatch flushed = new CountDownLatch(1), finishBody = new CountDownLatch(1);
        String terminal = empty ? completed() : completed(call("a", "first"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write((terminal + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                flushed.countDown();
                finishBody.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.start();
        ResponsesClient client = new ResponsesClient(JSON, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "", Duration.ofSeconds(1));
        CompletableFuture<ModelClient.ModelReply> pending = CompletableFuture.supplyAsync(() -> client.complete(request, ignored -> {}));
        try {
            // 终止事件已送达，响应体仍保持打开；此时既不能交付工具，也不能判空重试。
            assertThat(flushed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> pending.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        } finally {
            finishBody.countDown();
        }
        if (empty) {
            assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(ModelClient.EmptyResponse.class);
        } else {
            assertThat(pending.get(3, TimeUnit.SECONDS).calls()).extracting(Message.ToolCall::id).containsExactly("a");
        }
    }
}
