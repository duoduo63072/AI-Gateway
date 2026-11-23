package com.company.aigateway.proxy;

import com.company.aigateway.config.DashScopeProperties;
import com.company.aigateway.config.GatewayProperties;
import com.company.aigateway.persistence.CapturePersistenceService;
import com.company.aigateway.persistence.entity.ChatLog;
import com.company.aigateway.quota.QuotaService;
import com.company.aigateway.security.AuthenticatedClient;
import com.company.aigateway.token.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 代理核心：将客户端 OpenAI 格式请求转发到百炼兼容模式。
 * 流式路径用旁路 Tee（doOnNext 累积 + doFinally 落库）做到即收即转、不阻塞；
 * 非流式路径整体拿到响应后透传并落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatProxyService {

    private final WebClient dashScopeWebClient;
    private final DashScopeProperties dashScopeProperties;
    private final GatewayProperties gatewayProperties;
    private final CapturePersistenceService persistenceService;
    private final TokenEstimator tokenEstimator;
    private final QuotaService quotaService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public Mono<ServerResponse> proxy(JsonNode requestBody, AuthenticatedClient client) {
        boolean stream = requestBody.path("stream").asBoolean(false);
        String requestedModel = requestBody.path("model").asText("");
        String upstreamModel = resolveUpstreamModel(requestedModel);

        RequestContext ctx = new RequestContext(
                UUID.randomUUID(), client, requestedModel, upstreamModel, stream, requestBody);
        tagSpan(ctx);

        ObjectNode upstreamBody = buildUpstreamBody(requestBody, upstreamModel, stream);

        // 成本额度检查：超额且为会话第一轮时，置注入标记；不拦截、照常透传。fail-open 见 QuotaService。
        Long clientId = client == null ? null : client.clientId();
        return quotaService.warningFor(clientId, requestBody)
                .doOnNext(text -> {
                    ctx.setInjectQuotaWarning(true);
                    ctx.setQuotaWarningText(text);
                })
                .then(Mono.defer(() -> stream ? streamingProxy(upstreamBody, ctx) : nonStreamingProxy(upstreamBody, ctx)));
    }

    // ---------------------------------------------------------------- 流式

    private Mono<ServerResponse> streamingProxy(ObjectNode upstreamBody, RequestContext ctx) {
        StreamAccumulator acc = new StreamAccumulator(
                objectMapper, gatewayProperties.getCapture().getMaxCompletionChars(), ctx.getStartNanos());

        Flux<String> upstream = dashScopeWebClient.post()
                .uri(dashScopeProperties.getChatPath())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(upstreamBody)
                .exchangeToFlux(resp -> {
                    ctx.setHttpStatus(resp.statusCode().value());
                    if (resp.statusCode().isError()) {
                        ctx.setErrored(true);
                        ctx.setErrorType("upstream_error");
                        return resp.bodyToMono(String.class).defaultIfEmpty("{}")
                                .doOnNext(ctx::setErrorMessage)
                                .flux();
                    }
                    return resp.bodyToFlux(String.class);
                });

        AtomicBoolean warningInjected = new AtomicBoolean(false);

        Flux<ServerSentEvent<String>> sse = upstream
                .doOnNext(payload -> {
                    acc.recordFirstToken();
                    acc.accept(payload);
                })
                // concatMap 在累加器(doOnNext)之后：注入的 chunk 不进语料、不计费；保持顺序。
                .concatMap(payload -> expandWithWarning(payload, ctx, warningInjected))
                .doFinally(signal -> finalizeStreaming(ctx, acc, signal.name()));

        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(BodyInserters.fromServerSentEvents(sse));
    }

    private void finalizeStreaming(RequestContext ctx, StreamAccumulator acc, String signal) {
        // signal 取自 reactor SignalType.name(): ON_COMPLETE / CANCEL / ON_ERROR
        String status;
        if (ctx.isErrored()) {
            status = "ERROR";
        } else if ("CANCEL".equals(signal) || "ON_ERROR".equals(signal)) {
            status = "PARTIAL"; // 客户端断开或流中途异常，已拼接部分仍有微调价值
        } else {
            status = "SUCCESS";
        }
        Integer ttft = acc.getTtftMs();
        if (ttft != null) {
            meterRegistry.timer("gen_ai.server.time_to_first_token",
                    "gen_ai.request.model", ctx.getUpstreamModel()).record(ttft, TimeUnit.MILLISECONDS);
        }
        capture(ctx, status, acc.getContent(), "assistant",
                acc.getUpstreamId(), acc.getFinishReason(), acc.getToolCallsJson(),
                acc.getPromptTokens(), acc.getCompletionTokens(), acc.getTotalTokens(), ttft);
    }

    // ---------------------------------------------------------------- 非流式

    private Mono<ServerResponse> nonStreamingProxy(ObjectNode upstreamBody, RequestContext ctx) {
        return dashScopeWebClient.post()
                .uri(dashScopeProperties.getChatPath())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(upstreamBody)
                .exchangeToMono(resp -> resp.bodyToMono(JsonNode.class)
                        .defaultIfEmpty(objectMapper.createObjectNode())
                        .flatMap(json -> {
                            ctx.setHttpStatus(resp.statusCode().value());
                            finalizeNonStreaming(ctx, json, resp.statusCode().isError());
                            // 落库后再注入：告警文本不进语料。仅对成功响应注入。
                            if (ctx.isInjectQuotaWarning() && !resp.statusCode().isError()) {
                                appendWarningToMessage(json, ctx.getQuotaWarningText());
                            }
                            return ServerResponse.status(resp.statusCode())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(json);
                        }));
    }

    private void finalizeNonStreaming(RequestContext ctx, JsonNode json, boolean error) {
        if (error) {
            ctx.setErrored(true);
            ctx.setErrorType("upstream_error");
            ctx.setErrorMessage(json.toString());
            capture(ctx, "ERROR", null, "assistant", textOrNull(json.get("id")),
                    null, null, null, null, null, null);
            return;
        }
        JsonNode choice = json.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").isTextual() ? message.get("content").asText() : null;
        String finishReason = textOrNull(choice.get("finish_reason"));
        JsonNode toolCallsNode = message.get("tool_calls");
        String toolCallsJson = (toolCallsNode != null && !toolCallsNode.isNull()) ? toolCallsNode.toString() : null;

        JsonNode usage = json.get("usage");
        Integer pt = null, ct = null, tt = null;
        if (usage != null && usage.hasNonNull("prompt_tokens")) {
            pt = usage.path("prompt_tokens").asInt();
            ct = usage.path("completion_tokens").asInt();
            tt = usage.path("total_tokens").asInt(pt + ct);
        }
        capture(ctx, "SUCCESS", content, "assistant", textOrNull(json.get("id")),
                finishReason, toolCallsJson, pt, ct, tt, null);
    }

    // ---------------------------------------------------------------- 公共落库

    private void capture(RequestContext ctx, String status, String completion, String role,
                         String upstreamId, String finishReason, String toolCallsJson,
                         Integer upstreamPrompt, Integer upstreamCompletion, Integer upstreamTotal,
                         Integer ttftMs) {

        Integer pt, ct, tt;
        String tokenSource;
        if (upstreamPrompt != null) {
            pt = upstreamPrompt;
            ct = upstreamCompletion;
            tt = upstreamTotal != null ? upstreamTotal
                    : (pt + (ct == null ? 0 : ct));
            tokenSource = "UPSTREAM";
        } else {
            pt = tokenEstimator.estimateMessages(ctx.getRequestBody().get("messages"));
            ct = tokenEstimator.estimateText(completion);
            tt = pt + ct;
            tokenSource = "ESTIMATED";
        }

        ChatLog logEntry = ChatLog.builder()
                .requestId(ctx.getRequestId())
                .upstreamRequestId(upstreamId)
                .traceId(ctx.getTraceId())
                .spanId(ctx.getSpanId())
                .clientId(ctx.getClient() == null ? null : ctx.getClient().clientId())
                .employeeId(ctx.getClient() == null ? null : ctx.getClient().employeeId())
                .modelRequested(ctx.getRequestedModel())
                .modelUpstream(ctx.getUpstreamModel())
                .isStream(ctx.isStream())
                .requestMessages(messagesJson(ctx))
                .requestParams(paramsJson(ctx))
                .completionContent(completion)
                .completionRole(role)
                .toolCalls(toJson(toolCallsJson))
                .finishReason(finishReason)
                .promptTokensEst(pt)
                .completionTokensEst(ct)
                .totalTokensEst(tt)
                .tokenSource(tokenSource)
                .status(status)
                .httpStatus(ctx.getHttpStatus())
                .errorType(ctx.getErrorType())
                .errorMessage(ctx.getErrorMessage())
                .ttftMs(ttftMs)
                .durationMs(ctx.elapsedMs())
                .createdAt(ctx.getCreatedAt())
                .finishedAt(OffsetDateTime.now())
                .build();

        persistenceService.persistAsync(logEntry);
        recordTokenMetrics(ctx.getUpstreamModel(), pt, ct);
    }

    // ---------------------------------------------------------------- 辅助

    private String resolveUpstreamModel(String requested) {
        var mapping = gatewayProperties.getModelMapping();
        String mapped = mapping.getMappings().get(requested);
        if (mapped != null) {
            return mapped;
        }
        if (requested != null && requested.startsWith("qwen")) {
            return requested; // 已是百炼模型，直接透传
        }
        return mapping.getDefaultModel();
    }

    private ObjectNode buildUpstreamBody(JsonNode requestBody, String upstreamModel, boolean stream) {
        ObjectNode body = requestBody.isObject()
                ? ((ObjectNode) requestBody).deepCopy()
                : objectMapper.createObjectNode();
        body.put("model", upstreamModel);
        if (stream) {
            ObjectNode streamOptions = (body.get("stream_options") instanceof ObjectNode existing)
                    ? existing : objectMapper.createObjectNode();
            streamOptions.put("include_usage", true);
            body.set("stream_options", streamOptions);
        }
        return body;
    }

    private void tagSpan(RequestContext ctx) {
        var span = tracer.currentSpan();
        if (span == null) {
            return;
        }
        span.tag("gen_ai.system", "dashscope");
        span.tag("gen_ai.request.model", ctx.getUpstreamModel());
        span.tag("gateway.request_id", ctx.getRequestId().toString());
        ctx.setTraceId(span.context().traceId());
        ctx.setSpanId(span.context().spanId());
    }

    private void recordTokenMetrics(String model, Integer promptTokens, Integer completionTokens) {
        if (promptTokens != null) {
            meterRegistry.counter("gen_ai.client.token.usage",
                    "gen_ai.system", "dashscope", "gen_ai.request.model", model,
                    "gen_ai.token.type", "input").increment(promptTokens);
        }
        if (completionTokens != null) {
            meterRegistry.counter("gen_ai.client.token.usage",
                    "gen_ai.system", "dashscope", "gen_ai.request.model", model,
                    "gen_ai.token.type", "output").increment(completionTokens);
        }
    }

    private Json messagesJson(RequestContext ctx) {
        JsonNode messages = ctx.getRequestBody().get("messages");
        return (messages == null || messages.isNull()) ? Json.of("[]") : Json.of(messages.toString());
    }

    private Json paramsJson(RequestContext ctx) {
        JsonNode body = ctx.getRequestBody();
        if (!body.isObject()) {
            return null;
        }
        ObjectNode params = ((ObjectNode) body).deepCopy();
        params.remove("messages");
        return Json.of(params.toString());
    }

    private Json toJson(String json) {
        return json == null ? null : Json.of(json);
    }

    private String textOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asText();
    }

    private String bearer() {
        return "Bearer " + dashScopeProperties.getApiKey();
    }

    // ---------------------------------------------------------------- 超额告警注入

    /**
     * 透传单个 SSE chunk；若需注入告警，则在带 finish_reason 的收尾 chunk 之前补一个 content-delta chunk，
     * 使告警显示在回答末尾。非 JSON / [DONE] / 异常都原样透传(fail-open)。
     */
    private Flux<ServerSentEvent<String>> expandWithWarning(String payload, RequestContext ctx, AtomicBoolean injected) {
        ServerSentEvent<String> passthrough = ServerSentEvent.<String>builder().data(payload).build();
        if (!ctx.isInjectQuotaWarning() || payload == null) {
            return Flux.just(passthrough);
        }
        String t = payload.trim();
        if (t.isEmpty() || "[DONE]".equals(t) || t.charAt(0) != '{') {
            return Flux.just(passthrough);
        }
        try {
            JsonNode node = objectMapper.readTree(t);
            JsonNode finishReason = node.path("choices").path(0).get("finish_reason");
            if (finishReason != null && !finishReason.isNull() && injected.compareAndSet(false, true)) {
                return Flux.just(
                        ServerSentEvent.<String>builder().data(buildWarningChunk(node, ctx.getQuotaWarningText())).build(),
                        passthrough);
            }
        } catch (Exception ignored) {
            // 解析失败：原样透传
        }
        return Flux.just(passthrough);
    }

    /** 非流式：把告警追加到 choices[0].message.content 末尾。 */
    private void appendWarningToMessage(JsonNode json, String text) {
        try {
            if (text == null) {
                return;
            }
            if (json.path("choices").path(0).path("message") instanceof ObjectNode msg) {
                String existing = msg.path("content").isTextual() ? msg.get("content").asText() : "";
                msg.put("content", existing + text);
            }
        } catch (Exception ignored) {
            // fail-open：注入失败不影响正常响应
        }
    }

    /** 仿照上游 chunk 造一个仅含告警文本的 content-delta chunk。 */
    private String buildWarningChunk(JsonNode template, String text) {
        ObjectNode chunk = objectMapper.createObjectNode();
        chunk.put("id", template.path("id").asText(""));
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", template.path("created").asLong(System.currentTimeMillis() / 1000));
        chunk.put("model", template.path("model").asText(""));
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        delta.put("content", text == null ? "" : text);
        choice.putNull("finish_reason");
        return chunk.toString();
    }
}
