package com.company.aigateway.config;

import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * 上游百炼 WebClient。responseTimeout 为相邻两次网络读之间的最大间隔（idle read），
 * SSE 下 token 持续到达会不断重置，因此不会误伤长流。
 */
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final DashScopeProperties dashScopeProperties;

    @Bean
    public WebClient dashScopeWebClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(dashScopeProperties.getResponseTimeout());

        return builder
                .baseUrl(dashScopeProperties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
