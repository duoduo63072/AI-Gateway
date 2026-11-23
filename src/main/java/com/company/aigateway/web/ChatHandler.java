package com.company.aigateway.web;

import com.company.aigateway.proxy.ChatProxyService;
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
 * /v1/chat/completions 入口：取出鉴权身份、做最小校验后交给代理服务。
 */
@Component
@RequiredArgsConstructor
public class ChatHandler {

    private static final String INVALID_BODY =
            "{\"error\":{\"message\":\"'messages' field is required\",\"type\":\"invalid_request_error\"}}";

    private final ChatProxyService proxyService;

    public Mono<ServerResponse> chatCompletions(ServerRequest request) {
        AuthenticatedClient client = (AuthenticatedClient) request.exchange()
                .getAttributes().get(ApiKeyAuthFilter.CLIENT_ATTRIBUTE);

        return request.bodyToMono(JsonNode.class)
                .flatMap(body -> {
                    if (body == null || !body.isObject() || !body.has("messages")) {
                        return ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(INVALID_BODY);
                    }
                    return proxyService.proxy(body, client);
                });
    }
}
