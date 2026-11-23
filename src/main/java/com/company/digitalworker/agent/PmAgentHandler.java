package com.company.digitalworker.agent;

import com.company.aigateway.agent.AgentHandler;
import com.company.aigateway.security.AuthenticatedClient;
import com.company.digitalworker.config.DigitalWorkerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * pm-agent 接入网关的处理器：命中 model（默认 "pm-agent"）时，取出问题与提问者身份，
 * 交给 {@link PmAgentService} 出答案，再按 Claude Code 需要的形态返回。
 * 流式（默认）走真流式：消费 {@link PmAgentService#answerStream} 的 token 增量逐帧吐成 Anthropic SSE；
 * 关掉 stream-enabled 则回退为"整段答案合成一段 SSE"。非流式返回标准 messages 响应。
 * 仅在 digital-worker 模块开启时注册。pm-agent 对话不采集进 chat_log（避免污染工作摘要）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "digital-worker", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PmAgentHandler implements AgentHandler {

    private final PmAgentService pmAgentService;
    private final DigitalWorkerProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String model) {
        return model != null && properties.getAgent().getModelNames().contains(model);
    }

    @Override
    public Mono<ServerResponse> handle(JsonNode requestBody, AuthenticatedClient client, boolean stream) {
        String model = requestBody.path("model").asText("pm-agent");
        String question = extractQuestion(requestBody);
        // 轻量审计：谁、什么角色、问了什么（截断）。不入 chat_log。
        log.info("pm-agent ask by employee={} role={}: {}",
                client == null ? "?" : client.employeeId(),
                client == null ? "?" : client.role(),
                question.length() > 120 ? question.substring(0, 120) + "…" : question);

        // 真流式：边生成边吐 token 增量（默认）。可用 stream-enabled=false 一键回退整段合成。
        if (stream && properties.getAgent().isStreamEnabled()) {
            return streamingReal(pmAgentService.answerStream(question, client), model);
        }
        Mono<String> answer = pmAgentService.answer(question, client);
        return stream ? streaming(answer, model) : nonStreaming(answer, model);
    }

    // ---------------------------------------------------------------- 非流式

    private Mono<ServerResponse> nonStreaming(Mono<String> answer, String model) {
        return answer.flatMap(text -> {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("id", messageId());
            body.put("type", "message");
            body.put("role", "assistant");
            body.put("model", model);
            body.putArray("content").addObject().put("type", "text").put("text", text);
            body.put("stop_reason", "end_turn");
            body.putNull("stop_sequence");
            ObjectNode usage = body.putObject("usage");
            usage.put("input_tokens", 0);
            usage.put("output_tokens", 0);
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        });
    }

    // ---------------------------------------------------------------- 真流式（逐 token 透传）

    /** 消费 answerStream 的 text 增量：先发 message_start/content_block_start，每个增量一帧 delta，末尾收尾。 */
    private Mono<ServerResponse> streamingReal(Flux<String> deltas, String model) {
        Flux<ServerSentEvent<String>> head = Flux.just(
                event("message_start", messageStartNode(messageId(), model)),
                event("content_block_start", blockStartNode()));
        Flux<ServerSentEvent<String>> body = deltas
                .filter(s -> s != null && !s.isEmpty())
                .switchIfEmpty(Flux.just("抱歉，我没能整理出结果。")) // 跑完无任何文本时兜底
                .map(piece -> event("content_block_delta", textDeltaNode(piece)));
        Flux<ServerSentEvent<String>> tail = Flux.just(
                event("content_block_stop", blockStopNode()),
                event("message_delta", messageDeltaNode()),
                event("message_stop", messageStopNode()));
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(BodyInserters.fromServerSentEvents(Flux.concat(head, body, tail)));
    }

    // ---------------------------------------------------------------- 回退：整段答案合成一段 Anthropic SSE

    private Mono<ServerResponse> streaming(Mono<String> answer, String model) {
        Flux<ServerSentEvent<String>> sse = answer.flatMapMany(text -> Flux.just(
                event("message_start", messageStartNode(messageId(), model)),
                event("content_block_start", blockStartNode()),
                event("content_block_delta", textDeltaNode(text)),
                event("content_block_stop", blockStopNode()),
                event("message_delta", messageDeltaNode()),
                event("message_stop", messageStopNode())));
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(BodyInserters.fromServerSentEvents(sse));
    }

    // ---------------------------------------------------------------- Anthropic SSE 事件节点

    private ObjectNode messageStartNode(String id, String model) {
        ObjectNode startMsg = objectMapper.createObjectNode();
        startMsg.put("type", "message_start");
        ObjectNode msg = startMsg.putObject("message");
        msg.put("id", id);
        msg.put("type", "message");
        msg.put("role", "assistant");
        msg.put("model", model);
        msg.putArray("content");
        msg.putNull("stop_reason");
        msg.putNull("stop_sequence");
        ObjectNode usage = msg.putObject("usage");
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        return startMsg;
    }

    private ObjectNode blockStartNode() {
        ObjectNode blockStart = objectMapper.createObjectNode();
        blockStart.put("type", "content_block_start");
        blockStart.put("index", 0);
        blockStart.putObject("content_block").put("type", "text").put("text", "");
        return blockStart;
    }

    private ObjectNode textDeltaNode(String text) {
        ObjectNode delta = objectMapper.createObjectNode();
        delta.put("type", "content_block_delta");
        delta.put("index", 0);
        delta.putObject("delta").put("type", "text_delta").put("text", text);
        return delta;
    }

    private ObjectNode blockStopNode() {
        ObjectNode blockStop = objectMapper.createObjectNode();
        blockStop.put("type", "content_block_stop");
        blockStop.put("index", 0);
        return blockStop;
    }

    private ObjectNode messageDeltaNode() {
        ObjectNode msgDelta = objectMapper.createObjectNode();
        msgDelta.put("type", "message_delta");
        ObjectNode d = msgDelta.putObject("delta");
        d.put("stop_reason", "end_turn");
        d.putNull("stop_sequence");
        msgDelta.putObject("usage").put("output_tokens", 0);
        return msgDelta;
    }

    private ObjectNode messageStopNode() {
        ObjectNode msgStop = objectMapper.createObjectNode();
        msgStop.put("type", "message_stop");
        return msgStop;
    }

    private ServerSentEvent<String> event(String name, JsonNode data) {
        return ServerSentEvent.<String>builder().event(name).data(write(data)).build();
    }

    // ---------------------------------------------------------------- 辅助

    private String extractQuestion(JsonNode body) {
        JsonNode messages = body.path("messages");
        if (messages.isArray()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode m = messages.get(i);
                if ("user".equals(m.path("role").asText(""))) {
                    return contentToText(m.path("content"));
                }
            }
        }
        return "";
    }

    private String contentToText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : content) {
                if ("text".equals(b.path("type").asText(""))) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(b.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    private String messageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }
}
