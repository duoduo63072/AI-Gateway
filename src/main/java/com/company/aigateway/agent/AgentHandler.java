package com.company.aigateway.agent;

import com.company.aigateway.security.AuthenticatedClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * 网关扩展点：把命中特定 model 名的 /v1/messages 请求交给"数字员工"处理，而非透传上游。
 *
 * <p>接口定义在网关侧、由 digitalworker 实现并注册为 Bean——保持"网关不依赖数字员工、
 * 数字员工依赖网关"的单向关系。{@link MessagesProxyService} 注入全部实现，命中即委派。
 * 新增数字员工（HR/财务等）= 多一个实现，网关无需改动。
 */
public interface AgentHandler {

    /** 是否处理该 model（如 "pm-agent"）。 */
    boolean supports(String model);

    /**
     * 处理一次对话请求。实现方自行调上游、按身份取数、组织回答，并按 stream 返回
     * 合法的 Anthropic 响应（SSE 或非流式）。命中后网关不再透传、不采集进 chat_log。
     */
    Mono<ServerResponse> handle(JsonNode requestBody, AuthenticatedClient client, boolean stream);
}
