package com.company.aigateway.security;

import com.company.aigateway.config.GatewayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关侧 API Key 鉴权。通过后将 {@link AuthenticatedClient} 放入 exchange 属性，供下游 Handler 取用。
 * 放行 actuator 健康检查等公共路径。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class ApiKeyAuthFilter implements WebFilter {

    public static final String CLIENT_ATTRIBUTE = "gateway.authenticatedClient";
    private static final String BEARER_PREFIX = "Bearer ";

    private final GatewayProperties properties;
    private final ApiKeyService apiKeyService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!properties.getAuth().isEnabled() || isPublic(path)) {
            return chain.filter(exchange);
        }

        String token = extractBearer(exchange);
        if (token == null) {
            return unauthorized(exchange, "Missing or malformed Authorization header");
        }

        // 注意：switchIfEmpty 必须挂在 authenticate() 上，而非整条链上。
        // chain.filter() 返回 Mono<Void>（正常完成且不发元素），若 switchIfEmpty 挂在其后，
        // 会被「空完成」误判为未鉴权，在响应已提交后再调 unauthorized() → ReadOnlyHttpHeaders
        // 抛 UnsupportedOperationException，流式响应即被中断关连接。
        return apiKeyService.authenticate(token)
                .switchIfEmpty(Mono.defer(() ->
                        unauthorized(exchange, "Invalid API key").then(Mono.<AuthenticatedClient>empty())))
                .flatMap(client -> {
                    exchange.getAttributes().put(CLIENT_ATTRIBUTE, client);
                    return chain.filter(exchange);
                });
    }

    private boolean isPublic(String path) {
        // 仅放行健康检查匿名访问（SAE/CLB 探针打 /actuator/health/liveness|readiness）。
        // metrics/info/env 等其余 actuator 端点须鉴权——actuator 经 nginx 直达公网，
        // 不能让运维信息无鉴权泄露。即便日后误把它们加回 exposure，也有这层兜底。
        return path.startsWith("/actuator/health") || path.equals("/health") || path.equals("/favicon.ico");
    }

    private String extractBearer(ServerWebExchange exchange) {
        // Anthropic SDK (Claude Code) 使用 x-api-key，OpenAI 兼容客户端使用 Authorization: Bearer
        String xApiKey = exchange.getRequest().getHeaders().getFirst("x-api-key");
        if (xApiKey != null && !xApiKey.isBlank()) {
            return xApiKey.trim();
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":{\"message\":\"" + message
                + "\",\"type\":\"invalid_request_error\",\"code\":\"unauthorized\"}}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
