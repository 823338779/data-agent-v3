package com.datagent.model;

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

/** 仅发起一次Responses API 的 HTTP 调用，工具由 Runner 执行。 */
@Component
public class ResponsesClient implements ModelClient {
    static final String TYPE = "type", NAME = "name", ARGUMENTS = "arguments", CALL_ID = "call_id";
    static final String FUNCTION_CALL = "function_call", OUTPUT = "output", CONTENT = "content";
    static final String REASONING = "reasoning", MESSAGE = "message", MAX_OUTPUT_TOKENS = "max_output_tokens";
    private static final String ROLE = "role", MODEL = "model", STREAM = "stream", INPUT = "input";
    private static final String INSTRUCTIONS = "instructions", TOOLS = "tools", FUNCTION_OUTPUT = "function_call_output";
    private static final String STORE = "store", INCLUDE = "include", ENCRYPTED_REASONING = "reasoning.encrypted_content";
    private static final String AUTH_HEADER = "Authorization", BEARER = "Bearer ";
    private static final String CONTENT_TYPE = "Content-Type", JSON_TYPE = "application/json";
    /** 后端复用连接池，默认不跟随携带凭据的重定向。 */
    private final HttpClient http = HttpClient.newHttpClient();
    /** 兼容端点、后端凭据与等待响应体开始的期限。 */
    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    /** 消息与协议对象编解码器。 */
    private final ObjectMapper json;

    public ResponsesClient(ObjectMapper json,
            @Value("${agent.model.endpoint}") URI endpoint,
            @Value("${agent.model.token}") String token, @Value("${agent.model.timeout}") Duration timeout) {
        this.json = json;
        this.endpoint = endpoint;
        this.token = token;
        this.timeout = timeout;
    }

    @Override
    public ModelReply complete(ModelRequest request, Consumer<String> onText) {
        Map<String, Object> payload = wireRequest(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).header(CONTENT_TYPE, JSON_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (!token.isBlank()) {
            builder.header(AUTH_HEADER, BEARER + token);
        }
        // 启动期限覆盖连接和首字节；收到响应体后持续消费至本次 HTTP 响应结束。
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletionStream parser = new CompletionStream(json, onText, request.maxOutputTokens());
        HttpResponse.BodyHandler<Void> handler = info -> info.statusCode() == 200
                ? parser.bodySubscriber(started)
                : HttpResponse.BodySubscribers.discarding();
        CompletableFuture<HttpResponse<Void>> future = http.sendAsync(builder.build(), handler);
        try {
            CompletableFuture.anyOf(started, future).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            HttpResponse<Void> response = future.get();
            if (response.statusCode() != 200) {
                throw new IllegalStateException("模型服务返回 HTTP " + response.statusCode());
            }
            return parser.reply();
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new InterruptedResponse("模型请求超时", error);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型调用被中断", error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof HttpTimeoutException) {
                throw new InterruptedResponse("模型请求超时", error);
            }
            // HTTP 传输失败可重试，协议解析错误和正文回调异常仍由调用方处理。
            if (error.getCause() instanceof java.io.IOException) throw new InterruptedResponse("模型连接中断", error);
            throw new IllegalStateException("模型连接或响应异常", error);
        } finally {
            // 结束后只交付一次；即使回答截断，也保留已收到的实际用量。
            parser.close(request.usageListener());
        }
    }

    /** 预算和 HTTP 调用共用同一协议投影。 */
    public static Map<String, Object> wireRequest(ModelRequest request) {
        // 每次发送完整的无状态输入。推理项只在同一工具链回传，保留端点给出的标识和加密内容。
        List<Object> input = new ArrayList<>();
        for (int i = 0; i < request.messages().size(); i++) {
            Message message = request.messages().get(i);
            input.addAll(request.toolReasoning().getOrDefault(i, List.of()));
            if (Message.TOOL.equals(message.role())) {
                input.add(Map.of(TYPE, FUNCTION_OUTPUT, CALL_ID, message.toolCallId(), OUTPUT, message.content()));
                continue;
            }
            if (message.calls().isEmpty() || !message.content().isEmpty()) {
                input.add(Map.of(TYPE, MESSAGE, ROLE, message.role(), CONTENT, message.content()));
            }
            for (Message.ToolCall call : message.calls()) {
                input.add(Map.of(TYPE, FUNCTION_CALL, CALL_ID, call.id(), NAME, call.name(), ARGUMENTS, call.arguments()));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(MODEL, request.model(), INSTRUCTIONS, request.system(),
                INPUT, input, TOOLS, request.tools(), STREAM, true, STORE, false, INCLUDE, List.of(ENCRYPTED_REASONING)));
        if (request.maxOutputTokens() > 0) payload.put(MAX_OUTPUT_TOKENS, request.maxOutputTokens());
        return payload;
    }
}
