package com.datagent.v3.model;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 仅发起一次兼容 Chat Completions 的 HTTP 调用，工具由 Runner 执行。 */
@Component
public class ChatCompletionsClient implements ModelClient {
    static final String FUNCTION = "function", NAME = "name", ARGUMENTS = "arguments", ID = "id";
    static final String CALLS = "tool_calls", CONTENT = "content", STOP = "stop";
    private static final String MODEL = "model", STREAM = "stream", TOOLS = "tools", MESSAGES = "messages";
    private static final String MAX_TOKENS = "max_tokens";
    private static final String ROLE = "role", CALL_ID = "tool_call_id", TYPE = "type";
    private static final String AUTH_HEADER = "Authorization", BEARER = "Bearer ";
    private static final String CONTENT_TYPE = "Content-Type", JSON_TYPE = "application/json";
    /** 后端复用连接池，默认不跟随携带凭据的重定向。 */
    private final HttpClient http = HttpClient.newHttpClient();
    /** 兼容端点、后端凭据与单次完整调用期限。 */
    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    /** 消息与协议对象编解码器。 */
    private final ObjectMapper json;

    public ChatCompletionsClient(ObjectMapper json,
            @Value("${agent.model.endpoint:http://localhost:11434/v1/chat/completions}") URI endpoint,
            @Value("${agent.model.token:}") String token, @Value("${agent.model.timeout:120s}") Duration timeout) {
        this.json = json;
        this.endpoint = endpoint;
        this.token = token;
        this.timeout = timeout;
    }

    @Override
    public ModelReply complete(ModelRequest request, Consumer<String> onText) {
        var builder = HttpRequest.newBuilder(endpoint).timeout(timeout).header(CONTENT_TYPE, JSON_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(wireRequest(request))));
        if (!token.isBlank()) {
            builder.header(AUTH_HEADER, BEARER + token);
        }
        // Future 的完成覆盖响应体消费；到期取消底层 HTTP 交换。
        var parser = new CompletionStream(json, onText);
        HttpResponse.BodyHandler<Void> handler = info -> info.statusCode() == 200
                ? HttpResponse.BodySubscribers.fromLineSubscriber(parser, ignored -> null, StandardCharsets.UTF_8, null)
                : HttpResponse.BodySubscribers.discarding();
        var future = http.sendAsync(builder.build(), handler);
        try {
            var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("模型服务返回 HTTP " + response.statusCode());
            }
            return parser.reply();
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new IllegalStateException("模型请求超时", error);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型调用被中断", error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof HttpTimeoutException) {
                throw new IllegalStateException("模型请求超时", error);
            }
            throw new IllegalStateException("模型连接或响应异常", error);
        }
    }

    /** 预算和 HTTP 调用共用同一协议投影。 */
    public static Map<String, Object> wireRequest(ModelRequest request) {
        // 协议消息仅携带模型理解的字段，保留调用参数原文和结果关联。
        var history = new ArrayList<Map<String, Object>>();
        history.add(Map.of(ROLE, Message.SYSTEM, CONTENT, request.system()));
        request.messages().forEach(message -> history.add(toWire(message)));
        var payload = new LinkedHashMap<String, Object>(Map.of(MODEL, request.model(), STREAM, true,
                MESSAGES, history, TOOLS, request.tools()));
        if (request.maxOutputTokens() > 0) payload.put(MAX_TOKENS, request.maxOutputTokens());
        return payload;
    }

    private static Map<String, Object> toWire(Message message) {
        var wire = new LinkedHashMap<String, Object>();
        wire.put(ROLE, message.role());
        wire.put(CONTENT, message.content());
        if (!message.calls().isEmpty()) {
            wire.put(CALLS, message.calls().stream().map(call -> Map.of(ID, call.id(), TYPE, FUNCTION,
                    FUNCTION, Map.of(NAME, call.name(), ARGUMENTS, call.arguments()))).toList());
        }
        if (message.toolCallId() != null) {
            wire.put(CALL_ID, message.toolCallId());
        }
        return wire;
    }
}
