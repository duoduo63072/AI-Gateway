package com.company.aigateway.proxy;

import com.company.aigateway.agent.AgentHandler;
import com.company.aigateway.config.DashScopeProperties;
import com.company.aigateway.config.GatewayProperties;
import com.company.aigateway.persistence.CapturePersistenceService;
import com.company.aigateway.persistence.entity.ChatLog;
import com.company.aigateway.quota.QuotaService;
import com.company.aigateway.security.AuthenticatedClient;
import com.company.aigateway.token.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Anthropic Messages 代理核心：把 Claude Code 等 Anthropic 客户端的 /v1/messages 请求
 * 透传到百炼 Token Plan MaaS（上游同为 Anthropic 协议）。网关仅替换上游 Authorization、
 * 补齐 anthropic-version，并旁路采集语料——不做协议翻译。
 * 流式用 ServerSentEvent 中转以保真 event: 行；非流式整体拿到响应后透传并落库。
 * 按既定决策：completion_content 只存最终文本答案，thinking 内容不落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessagesProxyService {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient dashScopeWebClient;
    private final DashScopeProperties dashScopeProperties;
    private final GatewayProperties gatewayProperties;
    private final CapturePersistenceService persistenceService;
    private final TokenEstimator tokenEstimator;
    private final QuotaService quotaService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    /** 数字员工扩展点：命中特定 model 名则委派，不透传上游。无实现时为空列表，行为不变。 */
    private final List<AgentHandler> agentHandlers;

    public Mono<ServerResponse> proxy(JsonNode requestBody, AuthenticatedClient client,
                                      String anthropicVersion, String anthropicBeta) {
        boolean stream = requestBody.path("stream").asBoolean(false);
        String requestedModel = requestBody.path("model").asText("");

        // 数字员工路由：如 model=pm-agent，交给对应 AgentHandler，跳过透传与 chat_log 采集。
        for (AgentHandler handler : agentHandlers) {
            if (handler.supports(requestedModel)) {
                return handler.handle(requestBody, client, stream);
            }
        }

        String upstreamModel = resolveUpstreamModel(requestedModel);

        RequestContext ctx = new RequestContext(
                UUID.randomUUID(), client, requestedModel, upstreamModel, stream, requestBody);
        tagSpan(ctx);

        ObjectNode upstreamBody = buildUpstreamBody(requestBody, upstreamModel);

        // 成本额度检查：超额且为会话第一轮时，置注入标记；不拦截、照常透传。fail-open 见 QuotaService。
        Long clientId = client == null ? null : client.clientId();
        return quotaService.warningFor(clientId, requestBody)
                .doOnNext(text -> {
                    ctx.setInjectQuotaWarning(true);
                    ctx.setQuotaWarningText(text);
                })
                .then(Mono.defer(() -> stream
                        ? streamingProxy(upstreamBody, ctx, anthropicVersion, anthropicBeta)
                        : nonStreamingProxy(upstreamBody, ctx, anthropicVersion, anthropicBeta)));
    }

    // ---------------------------------------------------------------- count_tokens（纯透传，不采集）

    /**
     * /v1/messages/count_tokens：Claude Code 量算上下文 token 数。仅做模型映射 + 凭据注入的透传，
     * 不建 RequestContext、不落库（无补全内容可采集）。
     */
    public Mono<ServerResponse> countTokens(JsonNode requestBody, String anthropicVersion, String anthropicBeta) {
        String upstreamModel = resolveUpstreamModel(requestBody.path("model").asText(""));
        ObjectNode upstreamBody = buildUpstreamBody(requestBody, upstreamModel);
        return dashScopeWebClient.post()
                .uri(dashScopeProperties.getCountTokensPath())
                .headers(h -> applyUpstreamHeaders(h, anthropicVersion, anthropicBeta))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(upstreamBody)
                .exchangeToMono(resp -> resp.bodyToMono(JsonNode.class)
                        .defaultIfEmpty(objectMapper.createObjectNode())
                        .flatMap(json -> ServerResponse.status(resp.statusCode())
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(json)));
    }

    // ---------------------------------------------------------------- 流式

    private Mono<ServerResponse> streamingProxy(ObjectNode upstreamBody, RequestContext ctx,
                                                String anthropicVersion, String anthropicBeta) {
        AnthropicStreamAccumulator acc = new AnthropicStreamAccumulator(
                objectMapper, gatewayProperties.getCapture().getMaxCompletionChars(), ctx.getStartNanos());

        Flux<ServerSentEvent<String>> upstream = dashScopeWebClient.post()
                .uri(dashScopeProperties.getMessagesPath())
                .headers(h -> applyUpstreamHeaders(h, anthropicVersion, anthropicBeta))
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
                                .map(body -> ServerSentEvent.<String>builder()
                                        .event("error").data(body).build())
                                .flux();
                    }
                    // 保真中转：ParameterizedTypeReference<ServerSentEvent<String>> 保留 event: 行
                    return resp.bodyToFlux(SSE_TYPE);
                });

        // 注入用：记录已见最大 content block index、保证告警只注入一次。
        AtomicInteger maxBlockIndex = new AtomicInteger(-1);
        AtomicBoolean warningInjected = new AtomicBoolean(false);

        Flux<ServerSentEvent<String>> sse = upstream
                .doOnNext(event -> {
                    acc.recordFirstToken();
                    acc.accept(event.data());
                })
                // concatMap 在累加器(doOnNext)之后：注入的事件不进语料、不计费；保持事件顺序。
                .concatMap(event -> expandWithWarning(event, ctx, maxBlockIndex, warningInjected))
                .doFinally(signal -> finalizeStreaming(ctx, acc, signal.name()));

        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(BodyInserters.fromServerSentEvents(sse));
    }

    private void finalizeStreaming(RequestContext ctx, AnthropicStreamAccumulator acc, String signal) {
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
        capture(ctx, status, emptyToNull(acc.getContent()), "assistant",
                acc.getUpstreamId(), acc.getFinishReason(), acc.getToolCallsJson(),
                acc.getPromptTokens(), acc.getCompletionTokens(), acc.getTotalTokens(), ttft);
    }

    // ---------------------------------------------------------------- 非流式

    private Mono<ServerResponse> nonStreamingProxy(ObjectNode upstreamBody, RequestContext ctx,
                                                   String anthropicVersion, String anthropicBeta) {
        return dashScopeWebClient.post()
                .uri(dashScopeProperties.getMessagesPath())
                .headers(h -> applyUpstreamHeaders(h, anthropicVersion, anthropicBeta))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(upstreamBody)
                .exchangeToMono(resp -> resp.bodyToMono(JsonNode.class)
                        .defaultIfEmpty(objectMapper.createObjectNode())
                        .flatMap(json -> {
                            ctx.setHttpStatus(resp.statusCode().value());
                            finalizeNonStreaming(ctx, json, resp.statusCode().isError());
                            // 落库后再注入：告警文本块不进语料。仅对成功响应注入。
                            if (ctx.isInjectQuotaWarning() && !resp.statusCode().isError()) {
                                appendWarningBlock(json, ctx.getQuotaWarningText());
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
        // Anthropic 非流式响应：content 为块数组，拼接 text 块、收集 tool_use 块、忽略 thinking 块
        StringBuilder text = new StringBuilder();
        ArrayNode toolUses = objectMapper.createArrayNode();
        JsonNode content = json.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    text.append(block.path("text").asText(""));
                } else if ("tool_use".equals(type)) {
                    toolUses.add(block);
                }
            }
        }
        String completion = emptyToNull(text.toString());
        String toolCallsJson = toolUses.isEmpty() ? null : toolUses.toString();
        String finishReason = textOrNull(json.get("stop_reason"));

        JsonNode usage = json.get("usage");
        Integer pt = null, ct = null, tt = null;
        if (usage != null && usage.hasNonNull("input_tokens")) {
            pt = usage.path("input_tokens").asInt();
            ct = usage.path("output_tokens").asInt(0);
            tt = pt + ct;
        }
        capture(ctx, "SUCCESS", completion, "assistant", textOrNull(json.get("id")),
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
        // 非「标准 Claude 名」(claude-*) 一律视为百炼模型 ID 直接透传：
        // 涵盖 qwen* / deepseek* / kimi* / glm* / MiniMax* 等，使客户端可真正切换这些模型。
        // 仅当 claude-* 名未命中映射、或 model 为空时，才回退默认模型。
        if (requested != null && !requested.isBlank() && !requested.startsWith("claude")) {
            return requested;
        }
        return mapping.getDefaultModel();
    }

    private ObjectNode buildUpstreamBody(JsonNode requestBody, String upstreamModel) {
        ObjectNode body = requestBody.isObject()
                ? ((ObjectNode) requestBody).deepCopy()
                : objectMapper.createObjectNode();
        body.put("model", upstreamModel);
        return body;
    }

    /** 注入上游凭据与协议版本头；客户端原始 Authorization 不外传。 */
    private void applyUpstreamHeaders(HttpHeaders h, String anthropicVersion, String anthropicBeta) {
        h.set(HttpHeaders.AUTHORIZATION, bearer());
        String version = (anthropicVersion != null && !anthropicVersion.isBlank())
                ? anthropicVersion : dashScopeProperties.getAnthropicVersion();
        h.set("anthropic-version", version);
        if (anthropicBeta != null && !anthropicBeta.isBlank()) {
            h.set("anthropic-beta", anthropicBeta); // 透传 beta 特性（如 prompt caching）
        }
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
        // request_params 保留 messages 之外的全部字段：system / tools / max_tokens / stream 等
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

    private String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    // ---------------------------------------------------------------- 超额告警注入

    /**
     * 透传单个 SSE 事件；若该请求需注入告警，则在 message_delta 之前补一组文本块事件
     * (content_block_start / _delta / _stop)，使告警显示在回答末尾。任何异常都退化为原样透传(fail-open)。
     */
    private Flux<ServerSentEvent<String>> expandWithWarning(ServerSentEvent<String> event, RequestContext ctx,
                                                            AtomicInteger maxBlockIndex, AtomicBoolean injected) {
        ServerSentEvent<String> passthrough = ServerSentEvent.<String>builder()
                .event(event.event()).id(event.id()).data(event.data()).build();
        if (!ctx.isInjectQuotaWarning() || event.data() == null) {
            return Flux.just(passthrough);
        }
        try {
            JsonNode node = objectMapper.readTree(event.data().trim());
            String type = node.path("type").asText("");
            if ("content_block_start".equals(type)) {
                maxBlockIndex.updateAndGet(prev -> Math.max(prev, node.path("index").asInt(0)));
            } else if ("message_delta".equals(type) && injected.compareAndSet(false, true)) {
                int idx = maxBlockIndex.get() + 1;
                return Flux.just(
                        buildBlockStart(idx),
                        buildBlockDelta(idx, ctx.getQuotaWarningText()),
                        buildBlockStop(idx),
                        passthrough);
            }
        } catch (Exception ignored) {
            // 非 JSON 事件(如 ping)或解析失败：原样透传
        }
        return Flux.just(passthrough);
    }

    /** 非流式：往 Anthropic 响应的 content 数组末尾追加一个 text 块。形态异常则跳过，不破坏响应。 */
    private void appendWarningBlock(JsonNode json, String text) {
        try {
            if (text == null || !(json instanceof ObjectNode obj)) {
                return;
            }
            if (!(obj.get("content") instanceof ArrayNode content)) {
                return;
            }
            ObjectNode block = content.addObject();
            block.put("type", "text");
            block.put("text", text);
        } catch (Exception ignored) {
            // fail-open：注入失败不影响正常响应
        }
    }

    private ServerSentEvent<String> sseEvent(String eventName, ObjectNode data) {
        return ServerSentEvent.<String>builder().event(eventName).data(data.toString()).build();
    }

    private ServerSentEvent<String> buildBlockStart(int index) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("type", "content_block_start");
        n.put("index", index);
        ObjectNode cb = n.putObject("content_block");
        cb.put("type", "text");
        cb.put("text", "");
        return sseEvent("content_block_start", n);
    }

    private ServerSentEvent<String> buildBlockDelta(int index, String text) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("type", "content_block_delta");
        n.put("index", index);
        ObjectNode delta = n.putObject("delta");
        delta.put("type", "text_delta");
        delta.put("text", text == null ? "" : text);
        return sseEvent("content_block_delta", n);
    }

    private ServerSentEvent<String> buildBlockStop(int index) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("type", "content_block_stop");
        n.put("index", index);
        return sseEvent("content_block_stop", n);
    }

    private String bearer() {
        return "Bearer " + dashScopeProperties.getApiKey();
    }
}
