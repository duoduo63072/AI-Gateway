package com.company.digitalworker.agent;

import com.company.aigateway.config.DashScopeProperties;
import com.company.digitalworker.config.DigitalWorkerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * pm-agent 与百炼的对话调用：支持工具（tools / tool_use / tool_result），提供
 * <b>非流式</b>（{@link #call}）与 <b>流式</b>（{@link #callStream}）两种。复用网关的
 * {@code dashScopeWebClient} 与上游凭据；非流式由 PmAgentService 检查 stop_reason 决定
 * 执行工具还是收尾，流式则逐帧透传 text_delta / tool_use 增量。均带硬超时，避免上游挂起拖死整轮。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLlmClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient dashScopeWebClient;
    private final DashScopeProperties dashScopeProperties;
    private final DigitalWorkerProperties properties;
    private final ObjectMapper objectMapper;

    /** 发一次带 tools 的 Anthropic 报文，拿回完整响应（含 content 块与 stop_reason）。 */
    public Mono<JsonNode> call(JsonNode body) {
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
                .timeout(properties.getAgent().getTimeout())
                .doOnError(e -> log.warn("pm-agent llm call failed: {}", e.toString()));
    }

    /** 发一次带 tools 的 Anthropic 报文，以 SSE 流式拿回（text_delta / tool_use 增量与 stop_reason 逐帧到达）。 */
    public Flux<ServerSentEvent<String>> callStream(JsonNode body) {
        return dashScopeWebClient.post()
                .uri(dashScopeProperties.getMessagesPath())
                .headers(this::applyUpstreamHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchangeToFlux(resp -> {
                    if (resp.statusCode().isError()) {
                        return resp.bodyToMono(String.class).defaultIfEmpty("{}")
                                .flatMapMany(err -> Flux.error(new IllegalStateException(
                                        "upstream " + resp.statusCode().value() + ": " + err)));
                    }
                    return resp.bodyToFlux(SSE_TYPE);
                })
                .timeout(properties.getAgent().getTimeout())
                .doOnError(e -> log.warn("pm-agent llm stream failed: {}", e.toString()));
    }

    private void applyUpstreamHeaders(HttpHeaders h) {
        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + dashScopeProperties.getApiKey());
        h.set("anthropic-version", dashScopeProperties.getAnthropicVersion());
    }
}
