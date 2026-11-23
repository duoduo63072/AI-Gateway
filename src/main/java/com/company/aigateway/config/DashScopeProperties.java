package com.company.aigateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 上游阿里百炼接入配置。当前走 Token Plan 的 Anthropic 报文端点（/apps/anthropic/v1/messages），
 * 网关对 Claude Code 等 Anthropic 客户端做透传。
 * base-url 仅含 scheme+host：WebClient 在 base 带 path 时，{@code .uri("/x")} 会覆盖掉 base 的 path 段，
 * 故把完整路径放在 *-path 配置里，避免 /apps/anthropic 前缀丢失。
 * api-key 仅从环境变量注入，绝不入库、绝不打日志。
 */
@Data
@ConfigurationProperties(prefix = "gateway.dashscope")
public class DashScopeProperties {

    /** 上游基础地址（仅 scheme+host，路径见下方 *-path）。 */
    private String baseUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com";

    /** Anthropic Messages API 完整路径（Token Plan MaaS 端点）。 */
    private String messagesPath = "/apps/anthropic/v1/messages";

    /** Anthropic count_tokens 完整路径（Claude Code 量算上下文用，透传不采集）。 */
    private String countTokensPath = "/apps/anthropic/v1/messages/count_tokens";

    /** Anthropic 协议版本头默认值（客户端未带 anthropic-version 时使用）。 */
    private String anthropicVersion = "2023-06-01";

    /** OpenAI 兼容模式路径（已休眠：上游已切到 Anthropic 端点，此路不再走）。 */
    private String chatPath = "/compatible-mode/v1/chat/completions";

    /** 百炼 API Key，服务端注入到上游 Authorization 头。 */
    private String apiKey;

    /** 上游响应超时（SSE 长连接需放宽）。 */
    private Duration responseTimeout = Duration.ofSeconds(120);
}
