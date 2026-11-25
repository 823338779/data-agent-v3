package com.datagent.v3.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;
import com.datagent.v3.model.*;
import com.datagent.v3.session.PostgresTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionHttpIntegrationTest extends PostgresTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockitoBean ModelClient model;
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpRequest request(String path, String body) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/sessions" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        return (body == null ? builder.GET() : builder.POST(HttpRequest.BodyPublishers.ofString(body))).build();
    }
    private HttpResponse<String> send(String path, String body) throws Exception {
        return http.send(request(path, body), HttpResponse.BodyHandlers.ofString());
    }
    private String session() throws Exception {
        var response = send("", "");
        assertThat(response.statusCode()).isBetween(200, 201);
        return "/" + json.readTree(response.body()).get("sessionId").asString();
    }

    @Test void streamAndHttpErrorsHaveDistinctBoundaries() throws Exception {
        String path = session();
        assertThat(send(path + "/runs", "{\"message\":\"  \"}").statusCode()).isEqualTo(400);
        assertThat(send("/" + UUID.randomUUID(), null).statusCode()).isEqualTo(404);
        when(model.complete(any(), any())).thenAnswer(call -> {
            call.<Consumer<String>>getArgument(1).accept("OK");
            return new ModelClient.ModelReply("OK", List.of());
        });
        var result = send(path + "/runs", "{\"message\":\"hello\"}");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).containsSubsequence("event:run.started", "event:text.delta", "event:run.completed");
        assertThat(json.readTree(send(path, null).body()).at("/runs/0/messages/1/content").asString()).isEqualTo("OK");
        doThrow(new IllegalStateException("model unavailable")).when(model).complete(any(), any());
        assertThat(send(path + "/runs", "{\"message\":\"fail\"}").body()).contains("event:run.failed");
        assertThat(json.readTree(send(path, null).body()).at("/runs/1/status").asString()).isEqualTo("FAILED");
    }

    @Test void disconnectDoesNotCancelRun() throws Exception {
        var release = new CountDownLatch(1);
        when(model.complete(any(), any())).thenAnswer(ignored -> {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return new ModelClient.ModelReply("completed after disconnect", List.of());
        });
        String path = session();
        try (var body = http.send(request(path + "/runs", "{\"message\":\"wait\"}"), HttpResponse.BodyHandlers.ofInputStream()).body();
             var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
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
}
