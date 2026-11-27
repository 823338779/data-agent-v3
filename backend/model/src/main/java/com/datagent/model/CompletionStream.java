package com.datagent.model;

import static com.datagent.model.ResponsesClient.*;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 按 SSE 空行分帧，实时交付正文，完整工具调用以终止事件中的 output 为准。 */
final class CompletionStream implements Flow.Subscriber<String> {
    private static final String DATA = "data:", DONE = "[DONE]", DELTA = "delta", RESPONSE = "response";
    private static final String TEXT_DELTA = "response.output_text.delta", REFUSAL_DELTA = "response.refusal.delta";
    private static final String COMPLETED = "response.completed", FAILED = "response.failed", INCOMPLETE = "response.incomplete";
    private static final String ERROR = "error", STATUS = "status", SUCCESS = "completed", DETAILS = "incomplete_details";
    private static final String REASON = "reason", CODE = "code", TEXT = "text", OUTPUT_TEXT = "output_text", REFUSAL = "refusal";
    private static final String USAGE = "usage", INPUT_TOKENS = "input_tokens", OUTPUT_TOKENS = "output_tokens";
    /** 单次交换的协议解码、可见文本回调和请求的输出预算。 */
    private final ObjectMapper json;
    private final Consumer<String> onText;
    private final int maxOutputTokens;
    /** 当前事件的 data 行，空行才提交事件。 */
    private final StringBuilder frame = new StringBuilder();
    /** 已收到的终止事件类型及快照，其中含实际用量、完整输出项和调用参数。 */
    private String terminalType;
    private JsonNode response;
    /** 调用方取消或完成后忽略迟到的分片。 */
    private boolean closed;

    CompletionStream(ObjectMapper json, Consumer<String> onText, int maxOutputTokens) {
        this.json = json;
        this.onText = onText;
        this.maxOutputTokens = maxOutputTokens;
    }

    /** 首批字节只解除启动超时；响应完成仍由 JDK 的行订阅器按 HTTP 消息边界判定。 */
    HttpResponse.BodySubscriber<Void> bodySubscriber(CompletableFuture<Void> started) {
        HttpResponse.BodySubscriber<Void> lines = HttpResponse.BodySubscribers.fromLineSubscriber(
                this, ignored -> null, StandardCharsets.UTF_8, null);
        return new HttpResponse.BodySubscriber<>() {
            @Override public CompletionStage<Void> getBody() { return lines.getBody(); }
            @Override public void onSubscribe(Flow.Subscription subscription) { lines.onSubscribe(subscription); }
            @Override public void onNext(List<ByteBuffer> buffers) {
                if (!started.isDone() && buffers.stream().anyMatch(ByteBuffer::hasRemaining)) started.complete(null);
                lines.onNext(buffers);
            }
            @Override public void onError(Throwable error) { lines.onError(error); }
            @Override public void onComplete() { lines.onComplete(); }
        };
    }

    public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
    public void onError(Throwable error) { /* HTTP Future 负责交付传输异常。 */ }
    public void onComplete() { /* EOF 不提交残帧，reply 会验证已收到终止事件。 */ }

    public synchronized void onNext(String line) {
        if (closed) return;
        if (line.isEmpty()) {
            if (!frame.isEmpty()) {
                String data = frame.toString();
                frame.setLength(0);
                accept(data);
            }
        } else if (line.startsWith(DATA)) {
            if (!frame.isEmpty()) frame.append('\n');
            int start = DATA.length();
            if (start < line.length() && line.charAt(start) == ' ') start++;
            frame.append(line, start, line.length());
        }
    }

    private void accept(String data) {
        // 个别端点会另发传输尾标记，业务结果仍需核实 Responses 终止事件。
        if (DONE.equals(data)) return;
        if (terminalType != null) throw new IllegalStateException("模型终止事件后仍返回数据");
        JsonNode event = json.readTree(data);
        String type = event.path(TYPE).asString("");
        switch (type) {
            case TEXT_DELTA, REFUSAL_DELTA -> {
                String text = event.path(DELTA).asString("");
                if (!text.isEmpty()) onText.accept(text);
            }
            case COMPLETED, INCOMPLETE, FAILED, ERROR -> {
                terminalType = type;
                response = ERROR.equals(type) ? event : event.path(RESPONSE);
            }
            default -> { /* 推理和函数参数仅从终止快照读取，增量不进入可见正文。 */ }
        }
    }

    private Long tokens(String field) {
        if (response == null) return null;
        JsonNode count = response.path(USAGE).path(field);
        return count.isIntegralNumber() && count.canConvertToLong() && count.asLong() >= 0 ? count.asLong() : null;
    }

    synchronized void close(BiConsumer<Long, Long> listener) {
        closed = true;
        Long input = tokens(INPUT_TOKENS), output = tokens(OUTPUT_TOKENS);
        if (listener != null && (input != null || output != null)) listener.accept(input, output);
    }

    ModelClient.ModelReply reply() {
        // 先确认响应状态，断流、生成失败和输出不足均不能触发工具。
        if (terminalType == null) throw new ModelClient.InterruptedResponse("模型响应流提前结束，缺少 Responses 终止事件", null);
        if (FAILED.equals(terminalType) || ERROR.equals(terminalType)) {
            JsonNode error = ERROR.equals(terminalType) ? response : response.path(ERROR);
            throw new IllegalStateException("模型服务生成失败，错误码：" + error.path(CODE).asString(""));
        }
        if (INCOMPLETE.equals(terminalType)) {
            String reason = response.path(DETAILS).path(REASON).asString("");
            if (MAX_OUTPUT_TOKENS.equals(reason)) throw new IllegalStateException("模型达到输出上限，回复被截断");
            throw new IllegalStateException("模型回复未完成，原因：" + reason);
        }
        if (!SUCCESS.equals(response.path(STATUS).asString("")) || !response.path(OUTPUT).isArray()) {
            throw new IllegalStateException("模型终止事件缺少完整的响应快照");
        }
        // Ollama 0.32.1 在耗尽预算时仍发 completed，无法区分恰好用满和截断，按截断处理。
        Long used = tokens(OUTPUT_TOKENS);
        if (maxOutputTokens > 0 && used != null && used >= maxOutputTokens) {
            throw new IllegalStateException("模型达到输出上限，回复可能被截断");
        }

        StringBuilder text = new StringBuilder();
        List<Message.ToolCall> calls = new ArrayList<>();
        List<JsonNode> reasoning = new ArrayList<>();
        Set<String> callIds = new HashSet<>();
        for (JsonNode item : response.path(OUTPUT)) {
            switch (item.path(TYPE).asString("")) {
                case MESSAGE -> {
                    for (JsonNode part : item.path(CONTENT)) {
                        switch (part.path(TYPE).asString("")) {
                            case OUTPUT_TEXT -> text.append(part.path(TEXT).asString(""));
                            case REFUSAL -> text.append(part.path(REFUSAL).asString(""));
                        }
                    }
                }
                case FUNCTION_CALL -> {
                    // call_id 关联工具回执；id 属于输出项，两者用途不同。
                    String id = item.path(CALL_ID).asString(""), name = item.path(NAME).asString("");
                    String arguments = item.path(ARGUMENTS).asString("");
                    if (id.isBlank() || !callIds.add(id) || name.isBlank() || arguments.isBlank()
                            || !SUCCESS.equals(item.path(STATUS).asString(SUCCESS))) {
                        throw new IllegalStateException("模型工具调用不完整或调用 ID 重复");
                    }
                    if (!json.readTree(arguments).isObject()) throw new IllegalStateException("模型工具参数必须是 JSON 对象");
                    calls.add(new Message.ToolCall(id, name, arguments));
                }
                case REASONING -> reasoning.add(item);
            }
        }
        if (calls.isEmpty() && text.toString().isBlank()) {
            throw new ModelClient.EmptyResponse(reasoning.isEmpty() ? "模型正常结束但没有回答" : "模型正常结束但仅返回推理");
        }
        return new ModelClient.ModelReply(text.toString(), List.copyOf(calls), calls.isEmpty() ? List.of() : List.copyOf(reasoning));
    }
}
