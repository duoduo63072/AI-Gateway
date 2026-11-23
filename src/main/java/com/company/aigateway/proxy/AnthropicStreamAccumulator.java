package com.company.aigateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anthropic Messages SSE 流旁路累加器：在 doOnNext 中被顺序调用（单订阅内无并发）。
 * 解析 message_start / content_block_* / message_delta 等事件，拼接最终文本答案、
 * 按 index 归并 tool_use 块、捕获 input/output tokens 与 stop_reason。
 * 按既定决策：thinking_delta / signature_delta 不落库（仅透传给客户端，不进语料）。
 * 绝不阻塞主转发链路。
 */
public class AnthropicStreamAccumulator {

    private final ObjectMapper mapper;
    private final int maxChars;
    private final long startNanos;

    private final StringBuilder content = new StringBuilder();
    // index -> tool_use 累积（仅 tool_use 块需要按 index 归并 partial_json）
    private final Map<Integer, ToolUse> toolUses = new LinkedHashMap<>();

    @Getter private String upstreamId;
    @Getter private String upstreamModel;
    @Getter private String finishReason;
    @Getter private Integer promptTokens;
    @Getter private Integer completionTokens;
    private boolean truncated;
    private long firstTokenNanos = -1;

    public AnthropicStreamAccumulator(ObjectMapper mapper, int maxChars, long startNanos) {
        this.mapper = mapper;
        this.maxChars = maxChars;
        this.startNanos = startNanos;
    }

    /** 在转发每个事件前调用，记录首字延迟。 */
    public void recordFirstToken() {
        if (firstTokenNanos < 0) {
            firstTokenNanos = System.nanoTime();
        }
    }

    /** 处理一个 SSE data 负载（ServerSentEvent.data()，已被解码剥离 "data:" 前缀）。 */
    public void accept(String payload) {
        if (payload == null) {
            return;
        }
        String t = payload.trim();
        if (t.isEmpty() || t.charAt(0) != '{') {
            return;
        }
        try {
            JsonNode node = mapper.readTree(t);
            String type = node.path("type").asText("");
            switch (type) {
                case "message_start" -> handleMessageStart(node.path("message"));
                case "content_block_start" -> handleBlockStart(node);
                case "content_block_delta" -> handleBlockDelta(node);
                case "message_delta" -> handleMessageDelta(node);
                default -> { /* content_block_stop / message_stop / ping / error：无需累积 */ }
            }
        } catch (Exception ignored) {
            // 单帧解析失败不影响转发与后续累积
        }
    }

    private void handleMessageStart(JsonNode message) {
        if (message.hasNonNull("id")) {
            upstreamId = message.get("id").asText();
        }
        if (message.hasNonNull("model")) {
            upstreamModel = message.get("model").asText();
        }
        JsonNode usage = message.get("usage");
        if (usage != null && usage.hasNonNull("input_tokens")) {
            promptTokens = usage.path("input_tokens").asInt();
            // message_start 的 output_tokens 仅是占位，最终以 message_delta 为准
            if (usage.hasNonNull("output_tokens")) {
                completionTokens = usage.path("output_tokens").asInt();
            }
        }
    }

    private void handleBlockStart(JsonNode node) {
        int idx = node.path("index").asInt(0);
        JsonNode block = node.path("content_block");
        if ("tool_use".equals(block.path("type").asText(""))) {
            ToolUse tu = toolUses.computeIfAbsent(idx, k -> new ToolUse());
            if (block.hasNonNull("id")) {
                tu.id = block.get("id").asText();
            }
            if (block.hasNonNull("name")) {
                tu.name = block.get("name").asText();
            }
        }
    }

    private void handleBlockDelta(JsonNode node) {
        int idx = node.path("index").asInt(0);
        JsonNode delta = node.path("delta");
        switch (delta.path("type").asText("")) {
            case "text_delta" -> appendContent(delta.path("text").asText(""));
            case "input_json_delta" -> {
                ToolUse tu = toolUses.computeIfAbsent(idx, k -> new ToolUse());
                tu.inputJson.append(delta.path("partial_json").asText(""));
            }
            // thinking_delta / signature_delta：按决策不落库
            default -> { }
        }
    }

    private void handleMessageDelta(JsonNode node) {
        JsonNode delta = node.path("delta");
        if (delta.hasNonNull("stop_reason")) {
            finishReason = delta.get("stop_reason").asText();
        }
        JsonNode usage = node.get("usage");
        if (usage != null) {
            if (usage.hasNonNull("output_tokens")) {
                completionTokens = usage.path("output_tokens").asInt();
            }
            // 上游（百炼）会在 message_delta 复报 input_tokens 且口径更全（含 system/cache）。
            // 输入 token 数恒定，此处以终值覆盖 message_start 的初值，避免用量统计少计。
            if (usage.hasNonNull("input_tokens")) {
                promptTokens = usage.path("input_tokens").asInt();
            }
        }
    }

    private void appendContent(String s) {
        if (truncated || s == null || s.isEmpty()) {
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

    public String getContent() {
        return content.toString();
    }

    public Integer getTotalTokens() {
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        return (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
    }

    public Integer getTtftMs() {
        return firstTokenNanos < 0 ? null : (int) ((firstTokenNanos - startNanos) / 1_000_000L);
    }

    /** 归并后的 tool_use 序列化为 Anthropic 原生 JSON 数组字符串；无则返回 null。 */
    public String getToolCallsJson() {
        if (toolUses.isEmpty()) {
            return null;
        }
        ArrayNode arr = mapper.createArrayNode();
        for (Map.Entry<Integer, ToolUse> e : toolUses.entrySet()) {
            ToolUse tu = e.getValue();
            ObjectNode o = arr.addObject();
            o.put("type", "tool_use");
            o.put("index", e.getKey());
            if (tu.id != null) {
                o.put("id", tu.id);
            }
            if (tu.name != null) {
                o.put("name", tu.name);
            }
            String raw = tu.inputJson.toString();
            if (raw.isEmpty()) {
                o.set("input", mapper.createObjectNode());
            } else {
                try {
                    o.set("input", mapper.readTree(raw));
                } catch (Exception ex) {
                    // partial_json 未闭合或解析失败：原样存字符串，保留语料
                    o.put("input_raw", raw);
                }
            }
        }
        return arr.toString();
    }

    private static final class ToolUse {
        private String id;
        private String name;
        private final StringBuilder inputJson = new StringBuilder();
    }
}
