package com.company.aigateway.web;

import com.company.aigateway.proxy.MessagesProxyService;
import com.company.aigateway.security.ApiKeyAuthFilter;
import com.company.aigateway.security.AuthenticatedClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * /v1/messages 入口（Anthropic Messages API）：取出鉴权身份、做最小校验，
 * 透传 anthropic-version / anthropic-beta 头后交给代理服务。
 */
@Component
@RequiredArgsConstructor
public class MessagesHandler {

    private static final String INVALID_BODY =
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"'messages' field is required\"}}";

    private final MessagesProxyService proxyService;

    public Mono<ServerResponse> messages(ServerRequest request) {
        AuthenticatedClient client = (AuthenticatedClient) request.exchange()
                .getAttributes().get(ApiKeyAuthFilter.CLIENT_ATTRIBUTE);

        String anthropicVersion = request.headers().firstHeader("anthropic-version");
        String anthropicBeta = request.headers().firstHeader("anthropic-beta");

        return request.bodyToMono(JsonNode.class)
                .flatMap(body -> {
                    if (body == null || !body.isObject() || !body.has("messages")) {
                        return ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(INVALID_BODY);
                    }
                    return proxyService.proxy(body, client, anthropicVersion, anthropicBeta);
                });
    }

    /** /v1/messages/count_tokens：透传给上游量算 token，不采集语料。 */
    public Mono<ServerResponse> countTokens(ServerRequest request) {
        String anthropicVersion = request.headers().firstHeader("anthropic-version");
        String anthropicBeta = request.headers().firstHeader("anthropic-beta");

        return request.bodyToMono(JsonNode.class)
                .flatMap(body -> {
                    if (body == null || !body.isObject() || !body.has("messages")) {
                        return ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(INVALID_BODY);
                    }
                    return proxyService.countTokens(body, anthropicVersion, anthropicBeta);
                });
    }
}
