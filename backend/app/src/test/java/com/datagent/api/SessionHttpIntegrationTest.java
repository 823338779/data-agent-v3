package com.datagent.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;
import com.datagent.model.*;
import com.datagent.session.PostgresTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionHttpIntegrationTest extends PostgresTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockitoBean ModelClient model;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpRequest request(String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/sessions" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        return (body == null ? builder.GET() : builder.POST(HttpRequest.BodyPublishers.ofString(body))).build();
    }
    private HttpResponse<String> send(String path, String body) throws Exception {
        return http.send(request(path, body), HttpResponse.BodyHandlers.ofString());
    }
    private String session() throws Exception {
        HttpResponse<String> response = send("", "");
        assertThat(response.statusCode()).isBetween(200, 201);
        return "/" + json.readTree(response.body()).get("sessionId").asString();
    }

    @Test void streamAndHttpErrorsHaveDistinctBoundaries() throws Exception {
        String path = session();
        assertThat(send(path + "/runs", "{\"message\":\"  \"}").statusCode()).isEqualTo(400);
        assertThat(send("/" + UUID.randomUUID(), null).statusCode()).isEqualTo(404);
        // 无效补充由会话模块拒绝，HTTP 入口负责返回 400。
        assertThat(send(path + "/runs/" + UUID.randomUUID() + "/steers", "{}").statusCode()).isEqualTo(400);
        when(model.complete(any(), any())).thenAnswer(call -> {
            call.<Consumer<String>>getArgument(1).accept("OK");
            return new ModelClient.ModelReply("OK", List.of());
        });
        HttpResponse<String> result = send(path + "/runs", "{\"message\":\"hello\"}");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).containsSubsequence("event:run.started", "event:text.delta", "event:run.completed");
        assertThat(json.readTree(send(path, null).body()).at("/runs/0/messages/1/content").asString()).isEqualTo("OK");
        doThrow(new IllegalStateException("model unavailable")).when(model).complete(any(), any());
        assertThat(send(path + "/runs", "{\"message\":\"fail\"}").body()).contains("event:run.failed");
        assertThat(json.readTree(send(path, null).body()).at("/runs/1/status").asString()).isEqualTo("FAILED");
    }

    @Test void disconnectDoesNotCancelRun() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(model.complete(any(), any())).thenAnswer(ignored -> {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return new ModelClient.ModelReply("completed after disconnect", List.of());
        });
        String path = session();
        try (InputStream body = http.send(request(path + "/runs", "{\"message\":\"wait\"}"), HttpResponse.BodyHandlers.ofInputStream()).body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            assertThat(reader.readLine()).isEqualTo("event:run.started");
            assertThat(send(path + "/runs", "{\"message\":\"second\"}").statusCode()).isEqualTo(409);
        } finally {
            release.countDown();
        }
        JsonNode snapshot;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            snapshot = json.readTree(send(path, null).body());
            if ("SUCCEEDED".equals(snapshot.at("/runs/0/status").asString())) break;
            Thread.sleep(30);
        } while (System.nanoTime() < end);
        assertThat(snapshot.at("/runs").size()).isEqualTo(1);
        assertThat(snapshot.at("/runs/0/messages").size()).isEqualTo(2);
        assertThat(snapshot.at("/runs/0/messages/1/content").asString()).isEqualTo("completed after disconnect");
    }

    @Test void steersAfterFirstDeltaWithoutStartingAnotherRun() throws Exception {
        CountDownLatch firstDelta = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String path = session();
        AtomicInteger requestCount = new java.util.concurrent.atomic.AtomicInteger();
        when(model.complete(any(), any())).thenAnswer(call -> {
            if (requestCount.incrementAndGet() == 1) {
                call.<Consumer<String>>getArgument(1).accept("UTC 02:00。");
                firstDelta.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                return new ModelClient.ModelReply("UTC 02:00。", List.of());
            }
            ModelClient.ModelRequest request = call.<ModelClient.ModelRequest>getArgument(0);
            assertThat(request.messages()).extracting(Message::content)
                    .containsSubsequence("UTC 02:00。", "[本轮补充] 也给我北京时间");
            return new ModelClient.ModelReply("北京时间 10:00。", List.of());
        });
        CompletableFuture<HttpResponse<String>> stream = http.sendAsync(request(path + "/runs", "{\"message\":\"现在 UTC 几点？\"}"),
                HttpResponse.BodyHandlers.ofString());
        String steerPath;
        String supplement = json.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID().toString(),
                "message", "也给我北京时间"));
        try {
            assertThat(firstDelta.await(5, TimeUnit.SECONDS)).isTrue();
            String id = json.readTree(send(path, null).body()).get("activeRunId").asString();
            steerPath = path + "/runs/" + id + "/steers";
            HttpResponse<String> accepted = send(steerPath, supplement);
            assertThat(accepted.statusCode()).isEqualTo(202);
            assertThat(json.readTree(accepted.body()).get("messageSeq").isNull()).isTrue();
            assertThat(send(steerPath, supplement).statusCode()).isEqualTo(200);
            assertThat(stream.isDone()).isFalse();
        } finally {
            release.countDown();
        }
        HttpResponse<String> response = stream.get(10, TimeUnit.SECONDS);
        assertThat(response.body()).containsSubsequence("event:text.delta", "event:run.reply_committed",
                "event:run.steer_incorporated", "event:run.completed");
        assertThat(response.body().split("event:run.completed", -1)).hasSize(2);
        JsonNode snapshot = json.readTree(send(path, null).body()).get("runs");
        assertThat(snapshot.size()).isEqualTo(1);
        assertThat(snapshot.at("/0/messages/1/content").asString()).isEqualTo("UTC 02:00。");
        assertThat(snapshot.at("/0/messages/2/content").asString()).isEqualTo("也给我北京时间");
        assertThat(snapshot.at("/0/messages/3/content").asString()).isEqualTo("北京时间 10:00。");
        assertThat(snapshot.at("/0/steers/0/messageSeq").asInt()).isEqualTo(2);
        HttpResponse<String> receipt = send(steerPath, supplement);
        assertThat(receipt.statusCode()).isEqualTo(200);
        assertThat(json.readTree(receipt.body()).get("runStatus").asString()).isEqualTo("SUCCEEDED");
        assertThat(send(steerPath, json.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID().toString(),
                "message", "不会开新一轮"))).statusCode()).isEqualTo(409);
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test void recentSessionsUseFirstQuestionAndLatestActivity() throws Exception {
        // 先建两个有提问的会话和一个空会话，再继续较早的会话。
        String first = session(), second = session(), empty = session();
        when(model.complete(any(), any())).thenReturn(new ModelClient.ModelReply("OK", List.of()));
        send(first + "/runs", "{\"message\":\"第一条提问\"}");
        send(second + "/runs", "{\"message\":\"另一个会话\"}");
        send(first + "/runs", "{\"message\":\"继续追问\"}");

        // 列表只呈现有消息的会话；追问改变顺序，标题仍取首条提问。
        HttpResponse<String> response = send("", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode rows = json.readTree(response.body());
        assertThat(rows.at("/0/id").asString()).isEqualTo(first.substring(1));
        assertThat(rows.at("/0/title").asString()).isEqualTo("第一条提问");
        assertThat(rows.at("/0/updatedAt").asString()).isNotBlank();
        assertThat(rows.at("/1/id").asString()).isEqualTo(second.substring(1));
        assertThat(response.body()).doesNotContain(empty.substring(1));
    }
}
