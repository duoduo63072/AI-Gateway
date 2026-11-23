package com.company.aigateway.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class ChatRouter {

    @Bean
    public RouterFunction<ServerResponse> chatRoutes(ChatHandler chatHandler,
                                                     MessagesHandler messagesHandler,
                                                     ModelsHandler modelsHandler) {
        return RouterFunctions.route()
                // Anthropic Messages API：Claude Code 经此走网关（含语料采集）
                .POST("/v1/messages", messagesHandler::messages)
                // count_tokens：上下文量算，纯透传不采集
                .POST("/v1/messages/count_tokens", messagesHandler::countTokens)
                // 模型列表：CC Switch「获取模型列表」一键拉取
                .GET("/v1/models", modelsHandler::listModels)
                // OpenAI 兼容入口（已休眠，保留以备回退）
                .POST("/v1/chat/completions", chatHandler::chatCompletions)
                .build();
    }
}
