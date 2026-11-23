package com.company.aigateway.proxy;

import com.company.aigateway.security.AuthenticatedClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 单次请求的可变上下文：贯穿转发与落库，承载计时、链路 ID 与错误状态。
 */
@Getter
public class RequestContext {

    private final UUID requestId;
    private final AuthenticatedClient client;
    private final String requestedModel;
    private final String upstreamModel;
    private final boolean stream;
    private final JsonNode requestBody;
    private final long startNanos;
    private final OffsetDateTime createdAt;

    @Setter private String traceId;
    @Setter private String spanId;
    @Setter private Integer httpStatus;
    @Setter private boolean errored;
    @Setter private String errorType;
    @Setter private String errorMessage;
    /** 该请求是否需要在回答末尾注入「超额用量告警」(超额 + 会话第一轮 时置 true)。 */
    @Setter private boolean injectQuotaWarning;
    /** 注入的告警文案；仅 injectQuotaWarning 为 true 时有效。 */
    @Setter private String quotaWarningText;

    public RequestContext(UUID requestId, AuthenticatedClient client, String requestedModel,
                          String upstreamModel, boolean stream, JsonNode requestBody) {
        this.requestId = requestId;
        this.client = client;
        this.requestedModel = requestedModel;
        this.upstreamModel = upstreamModel;
        this.stream = stream;
        this.requestBody = requestBody;
        this.startNanos = System.nanoTime();
        this.createdAt = OffsetDateTime.now();
    }

    public int elapsedMs() {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }
}
