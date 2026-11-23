package com.company.digitalworker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * pm-agent 流式工具循环用的轻量 Anthropic SSE 解码器。一轮上游流对应一个实例，
 * 单订阅内顺序调用、无并发：text_delta 增量经 onText 实时回调（透传给客户端），
 * tool_use 按 index 累积 id/name/input_json，并记录 stop_reason；轮末用
 * {@link #assistantContent()} 还原该轮 assistant turn 以回灌上下文。解析骨架对齐
 * 网关的 AnthropicStreamAccumulator。
 */
class AgentStreamDecoder {

    private final ObjectMapper mapper;
    private final StringBuilder text = new StringBuilder();
    private final Map<Integer, ToolUse> toolUses = new LinkedHashMap<>();
    private String stopReason;

    AgentStreamDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 处理一帧 SSE data 负载；text_delta 增量通过 onText 实时回调。 */
    void accept(String payload, Consumer<String> onText) {
        if (payload == null) {
            return;
        }
        String t = payload.trim();
        if (t.isEmpty() || t.charAt(0) != '{') {
            return;
        }
        try {
            JsonNode node = mapper.readTree(t);
            switch (node.path("type").asText("")) {
                case "content_block_start" -> {
                    JsonNode block = node.path("content_block");
                    if ("tool_use".equals(block.path("type").asText(""))) {
                        ToolUse tu = toolUses.computeIfAbsent(node.path("index").asInt(0), k -> new ToolUse());
                        if (block.hasNonNull("id")) {
                            tu.id = block.get("id").asText();
                        }
                        if (block.hasNonNull("name")) {
                            tu.name = block.get("name").asText();
                        }
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = node.path("delta");
                    switch (delta.path("type").asText("")) {
                        case "text_delta" -> {
                            String piece = delta.path("text").asText("");
                            if (!piece.isEmpty()) {
                                text.append(piece);
                                onText.accept(piece);
                            }
                        }
                        case "input_json_delta" -> toolUses
                                .computeIfAbsent(node.path("index").asInt(0), k -> new ToolUse())
                                .inputJson.append(delta.path("partial_json").asText(""));
                        default -> { } // thinking_delta / signature_delta：不透传
                    }
                }
                case "message_delta" -> {
                    JsonNode d = node.path("delta");
                    if (d.hasNonNull("stop_reason")) {
                        stopReason = d.get("stop_reason").asText();
                    }
                }
                default -> { } // message_start / content_block_stop / message_stop / ping
            }
        } catch (Exception ignored) {
            // 单帧解析失败不影响后续累积与透传
        }
    }

    String stopReason() {
        return stopReason;
    }

    boolean hasToolUse() {
        return !toolUses.isEmpty();
    }

    /** 还原该轮 assistant turn 的 content 数组（text 块 + tool_use 块），用于回灌 messages。 */
    ArrayNode assistantContent() {
        ArrayNode arr = mapper.createArrayNode();
        if (text.length() > 0) {
            arr.addObject().put("type", "text").put("text", text.toString());
        }
        for (ToolUse tu : toolUses.values()) {
            ObjectNode o = arr.addObject();
            o.put("type", "tool_use");
            o.put("id", tu.id == null ? "" : tu.id);
            o.put("name", tu.name == null ? "" : tu.name);
            o.set("input", parseInput(tu.inputJson.toString()));
        }
        return arr;
    }

    /** 解析后的工具调用列表（id/name/input），供本地执行。 */
    List<ToolCall> toolCalls() {
        List<ToolCall> calls = new ArrayList<>();
        for (ToolUse tu : toolUses.values()) {
            calls.add(new ToolCall(tu.id == null ? "" : tu.id,
                    tu.name == null ? "" : tu.name, parseInput(tu.inputJson.toString())));
        }
        return calls;
    }

    private JsonNode parseInput(String raw) {
        if (raw == null || raw.isEmpty()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    record ToolCall(String id, String name, JsonNode input) {
    }

    private static final class ToolUse {
        private String id;
        private String name;
        private final StringBuilder inputJson = new StringBuilder();
    }
}
