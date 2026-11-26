package com.datagent.model;

import static com.datagent.model.ChatCompletionsClient.*;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 单次请求的 SSE 解析状态，按 index 聚合交错的工具分片。 */
final class CompletionStream implements Flow.Subscriber<String> {
    private static final String DATA = "data:", DONE = "[DONE]", CHOICES = "choices", INDEX = "index";
    private static final String DELTA = "delta", REASON = "finish_reason", ERROR = "error";
    private static final String USAGE = "usage", INPUT = "prompt_tokens", OUTPUT = "completion_tokens";
    /** 最近一次有效的累计输入和输出读数，缺失时保留未知。 */
    private volatile Long inputTokens, outputTokens;
    private final ObjectMapper json;
    /** 对前端可见的文本增量回调。 */
    private final Consumer<String> onText;
    /** 当前 SSE 帧可能包含多行 data。 */
    private final StringBuilder frame = new StringBuilder();
    /** 本次请求的累计回答。 */
    private final StringBuilder text = new StringBuilder();
    /** 工具编排按模型给出的调用位置排序。 */
    private final SortedMap<Integer, CallParts> calls = new TreeMap<>();
    /** 模型结束原因及传输结束标记。 */
    private String reason;
    private boolean done;

    private static class CallParts {
        String id;
        final StringBuilder name = new StringBuilder();
        final StringBuilder args = new StringBuilder();
    }

    CompletionStream(ObjectMapper json, Consumer<String> onText) {
        this.json = json;
        this.onText = onText;
    }

    public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
    public void onError(Throwable error) { /* HTTP Future 将错误交回调用方。 */ }
    public void onComplete() { flush(); }

    public void onNext(String line) {
        if (line.isEmpty()) {
            flush();
        } else if (line.startsWith(DATA)) {
            if (!frame.isEmpty()) frame.append('\n');
            frame.append(line.substring(DATA.length()).stripLeading());
        }
    }

    private void flush() {
        if (frame.isEmpty()) return;
        String data = frame.toString();
        frame.setLength(0);
        if (DONE.equals(data)) {
            done = true;
            return;
        }
        var chunk = json.readTree(data);
        if (done || chunk.has(ERROR)) throw new IllegalStateException("模型流包含异常事件");
        // usage 可能随普通分片返回，也可能独占 choices 为空的尾帧；累计值不逐帧相加。
        var usage = chunk.path(USAGE);
        if (usage.isObject()) {
            var input = usage.path(INPUT);
            var output = usage.path(OUTPUT);
            if (validCount(input)) inputTokens = input.asLong();
            if (validCount(output)) outputTokens = output.asLong();
        }
        var choices = chunk.path(CHOICES);
        if (choices.isEmpty()) return;
        // 默认请求一个候选；usage 尾帧允许没有候选。
        var choice = choices.get(0);
        var delta = choice.path(DELTA);
        var content = delta.path(CONTENT);
        if (content.isString() && !content.asString().isEmpty()) {
            text.append(content.asString());
            onText.accept(content.asString());
        }
        for (var call : delta.path(CALLS)) addCall(call);
        if (choice.path(REASON).isString()) reason = choice.get(REASON).asString();
    }

    private static boolean validCount(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0;
    }

    void reportUsage(BiConsumer<Long, Long> listener) {
        if (listener != null && (inputTokens != null || outputTokens != null)) {
            listener.accept(inputTokens, outputTokens);
        }
    }

    private void addCall(JsonNode delta) {
        int index = delta.path(INDEX).asInt(-1);
        if (index < 0) throw new IllegalStateException("工具调用缺少位置");
        var parts = calls.computeIfAbsent(index, ignored -> new CallParts());
        if (delta.path(ID).isString() && !delta.get(ID).asString().isBlank()) {
            if (parts.id != null && !parts.id.equals(delta.get(ID).asString())) throw new IllegalStateException("调用 ID 冲突");
            parts.id = delta.get(ID).asString();
        }
        var function = delta.path(FUNCTION);
        if (function.path(NAME).isString()) parts.name.append(function.get(NAME).asString());
        if (function.path(ARGUMENTS).isString()) parts.args.append(function.get(ARGUMENTS).asString());
    }

    ModelClient.ModelReply reply() {
        // 完整终止帧和有效调用 ID 缺一不可，禁止执行半条调用。
        String expected = calls.isEmpty() ? STOP : CALLS;
        if (!done || !expected.equals(reason)) {
            throw new IllegalStateException("模型回复中断或被截断");
        }
        var complete = new ArrayList<Message.ToolCall>();
        var ids = new HashSet<String>();
        for (var call : calls.values()) {
            if (call.id == null || call.id.isBlank() || !ids.add(call.id) || call.name.isEmpty() || call.args.isEmpty()) {
                throw new IllegalStateException("工具调用信息不完整");
            }
            json.readTree(call.args.toString());
            complete.add(new Message.ToolCall(call.id, call.name.toString(), call.args.toString()));
        }
        return new ModelClient.ModelReply(text.toString(), complete);
    }
}
