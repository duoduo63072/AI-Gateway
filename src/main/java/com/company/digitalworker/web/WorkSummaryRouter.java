package com.company.digitalworker.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * 虚拟员工报告路由（/api/worker/**），与网关入口 /v1/** 分开。
 * 仅当 {@code digital-worker.enabled=true} 时注册——模块关闭则这些端点不存在，对网关零暴露。
 * 鉴权：经现有 ApiKeyAuthFilter 要求合法 Bearer，再由 Handler 限定为主密钥身份。
 */
@Configuration
@ConditionalOnProperty(prefix = "digital-worker", name = "enabled", havingValue = "true")
public class WorkSummaryRouter {

    @Bean
    public RouterFunction<ServerResponse> workerRoutes(WorkSummaryHandler handler) {
        return RouterFunctions.route()
                .GET("/api/worker/summaries", handler::summaries)
                .GET("/api/worker/projects", handler::projects)
                .POST("/api/worker/run", handler::run)
                .build();
    }
}
