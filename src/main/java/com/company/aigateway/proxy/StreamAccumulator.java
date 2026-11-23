package com.company.aigateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 流旁路累加器：在 doOnNext 中被顺序调用（单订阅内无并发），拼接完整 completion、
 * 按 index 归并 tool_calls、捕获末帧 usage 与 finish_reason。绝不阻塞主转发链路。
 */
public class StreamAccumulator {

    private final ObjectMapper mapper;
    private final int maxChars;
    private final long startNanos;

    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, ToolCall> toolCalls = new LinkedHashMap<>();

    @Getter private String upstreamId;
    @Getter private String upstreamModel;
    @Getter private String finishReason;
    @Getter private Integer promptTokens;
    @Getter private Integer completionTokens;
    @Getter private Integer totalTokens;
    private boolean truncated;
    private long firstTokenNanos = -1;

    public StreamAccumulator(ObjectMapper mapper, int maxChars, long startNanos) {
        this.mapper = mapper;
        this.maxChars = maxChars;
        this.startNanos = startNanos;
    }

    /** 在转发每个 chunk 前调用，记录首字延迟。 */
    public void recordFirstToken() {
        if (firstTokenNanos < 0) {
            firstTokenNanos = System.nanoTime();
        }
    }

    /** 处理一个 SSE data 负载（已被 WebClient 剥离 "data:" 前缀）。 */
    public void accept(String payload) {
        if (payload == null) {
            return;
        }
        String t = payload.trim();
        if (t.isEmpty() || "[DONE]".equals(t) || t.charAt(0) != '{') {
            return;
        }
        try {
            JsonNode node = mapper.readTree(t);
            if (node.hasNonNull("id")) {
                upstreamId = node.get("id").asText();
            }
            if (node.hasNonNull("model")) {
                upstreamModel = node.get("model").asText();
            }
            JsonNode usage = node.get("usage");
            if (usage != null && usage.isObject() && usage.hasNonNull("prompt_tokens")) {
                promptTokens = usage.path("prompt_tokens").asInt();
                completionTokens = usage.path("completion_tokens").asInt();
                totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);
            }
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode choice = choices.get(0);
                JsonNode fr = choice.get("finish_reason");
                if (fr != null && !fr.isNull()) {
                    finishReason = fr.asText();
                }
                JsonNode delta = choice.get("delta");
                if (delta != null) {
                    JsonNode c = delta.get("content");
                    if (c != null && c.isTextual()) {
                        appendContent(c.asText());
                    }
                    JsonNode tc = delta.get("tool_calls");
                    if (tc != null && tc.isArray()) {
                        mergeToolCalls(tc);
                    }
                }
            }
        } catch (Exception ignored) {
            // 单帧解析失败不影响转发与后续累积
        }
    }

    private void appendContent(String s) {
        if (truncated) {
            return;
        }
        if (content.length() + s.length() > maxChars) {
            int allow = Math.max(0, maxChars - content.length());
            content.append(s, 0, allow).append("\n...[TRUNCATED]");
            truncated = true;
        } else {
            content.append(s);
        }
    }

    private void mergeToolCalls(JsonNode arr) {
        for (JsonNode tcNode : arr) {
            int idx = tcNode.path("index").asInt(0);
            ToolCall tc = toolCalls.computeIfAbsent(idx, k -> new ToolCall());
            if (tcNode.hasNonNull("id")) {
                tc.id = tcNode.get("id").asText();
            }
            if (tcNode.hasNonNull("type")) {
                tc.type = tcNode.get("type").asText();
            }
            JsonNode fn = tcNode.get("function");
            if (fn != null) {
                if (fn.hasNonNull("name")) {
                    tc.name = fn.get("name").asText();
                }
                if (fn.hasNonNull("arguments")) {
                    tc.arguments.append(fn.get("arguments").asText());
                }
            }
        }
    }

    public String getContent() {
        return content.toString();
    }

    public boolean hasUpstreamUsage() {
        return promptTokens != null;
    }

    public Integer getTtftMs() {
        return firstTokenNanos < 0 ? null : (int) ((firstTokenNanos - startNanos) / 1_000_000L);
    }

    /** 归并后的 tool_calls 序列化为 JSON 数组字符串；无则返回 null。 */
    public String getToolCallsJson() {
        if (toolCalls.isEmpty()) {
            return null;
        }
        ArrayNode arr = mapper.createArrayNode();
        for (Map.Entry<Integer, ToolCall> e : toolCalls.entrySet()) {
            ToolCall tc = e.getValue();
            ObjectNode o = arr.addObject();
            o.put("index", e.getKey());
            if (tc.id != null) {
                o.put("id", tc.id);
            }
            o.put("type", tc.type == null ? "function" : tc.type);
            ObjectNode fo = o.putObject("function");
            fo.put("name", tc.name == null ? "" : tc.name);
            fo.put("arguments", tc.arguments.toString());
        }
        return arr.toString();
    }

    private static final class ToolCall {
        private String id;
        private String type = "function";
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
