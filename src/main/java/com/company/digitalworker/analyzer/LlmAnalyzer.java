package com.company.digitalworker.analyzer;

import com.company.aigateway.config.DashScopeProperties;
import com.company.digitalworker.config.DigitalWorkerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 调百炼上游做总结分析。<b>非流式</b>、整体拿结果即可，不经 MessagesProxyService——
 * 因此分析产生的内容<b>不会被采集进 chat_log</b>（不污染语料、不递归）。
 * 复用网关已配好的 {@code dashScopeWebClient} 与上游凭据 {@link DashScopeProperties}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmAnalyzer {

    private final WebClient dashScopeWebClient;
    private final DashScopeProperties dashScopeProperties;
    private final DigitalWorkerProperties workerProperties;
    private final ObjectMapper objectMapper;

    /**
     * 发一次非流式 Anthropic 报文给百炼，返回拼接后的文本与 token 用量。
     */
    public Mono<LlmResponse> analyze(String systemPrompt, String userPrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", workerProperties.getAnalysis().getModel());
        body.put("max_tokens", workerProperties.getAnalysis().getMaxTokens());
        body.put("stream", false);
        body.put("system", systemPrompt);
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        return dashScopeWebClient.post()
                .uri(dashScopeProperties.getMessagesPath())
                .headers(this::applyUpstreamHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(resp -> {
                    if (resp.statusCode().isError()) {
                        return resp.bodyToMono(String.class).defaultIfEmpty("{}")
                                .flatMap(err -> Mono.error(new IllegalStateException(
                                        "upstream " + resp.statusCode().value() + ": " + err)));
                    }
                    return resp.bodyToMono(JsonNode.class).defaultIfEmpty(objectMapper.createObjectNode());
                })
                .timeout(workerProperties.getAnalysis().getTimeout())
                .map(this::parse)
                .doOnError(e -> log.warn("llm analyze call failed: {}", e.toString()));
    }

    private LlmResponse parse(JsonNode json) {
        StringBuilder text = new StringBuilder();
        JsonNode content = json.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText(""))) {
                    text.append(block.path("text").asText(""));
                }
            }
        }
        JsonNode usage = json.path("usage");
        Integer pt = usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asInt() : null;
        Integer ct = usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asInt() : null;
        return new LlmResponse(text.toString(), pt, ct);
    }

    private void applyUpstreamHeaders(HttpHeaders h) {
        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + dashScopeProperties.getApiKey());
        h.set("anthropic-version", dashScopeProperties.getAnthropicVersion());
    }

    /** 上游一次分析调用的返回。 */
    public record LlmResponse(String text, Integer promptTokens, Integer completionTokens) {
    }
}
