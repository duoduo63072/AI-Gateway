package com.company.aigateway.web;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * GET /v1/models：返回网关上游可用的模型清单（Anthropic Models 列表结构）。
 * 供 CC Switch 等客户端「获取模型列表」一键拉取并自动映射。
 * 鉴权由全局 {@link com.company.aigateway.security.ApiKeyAuthFilter} 统一处理。
 *
 * 清单为静态维护：含数字人项目经理（pm-agent）+ 阿里百炼可路由模型。
 * 新增/下线模型时改这里即可，无需对接上游 list 接口。
 */
@Component
public class ModelsHandler {

    private static final String MODELS_JSON = """
            {
              "data": [
                { "type": "model", "id": "pm-agent", "display_name": "数字人-项目经理 (pm-agent)" },
                { "type": "model", "id": "qwen3.7-max", "display_name": "千问 Qwen 3.7 Max (推理/文本)" },
                { "type": "model", "id": "qwen3.6-plus", "display_name": "千问 Qwen 3.6 Plus (推理/视觉/文本)" },
                { "type": "model", "id": "qwen3.6-flash", "display_name": "千问 Qwen 3.6 Flash (推理/视觉/文本)" },
                { "type": "model", "id": "qwen-image-2.0", "display_name": "千问 Qwen Image 2.0 (图片生成)" },
                { "type": "model", "id": "qwen-image-2.0-pro", "display_name": "千问 Qwen Image 2.0 Pro (图片生成)" },
                { "type": "model", "id": "wan2.7-image", "display_name": "万相 Wan 2.7 Image (图片生成)" },
                { "type": "model", "id": "wan2.7-image-pro", "display_name": "万相 Wan 2.7 Image Pro (图片生成)" },
                { "type": "model", "id": "deepseek-v4-pro", "display_name": "DeepSeek v4 Pro (推理/文本)" },
                { "type": "model", "id": "deepseek-v4-flash", "display_name": "DeepSeek v4 Flash (推理/文本)" },
                { "type": "model", "id": "deepseek-v3.2", "display_name": "DeepSeek v3.2 (推理/文本)" },
                { "type": "model", "id": "kimi-k2.6", "display_name": "月之暗面 Kimi k2.6 (推理/视觉/文本)" },
                { "type": "model", "id": "kimi-k2.5", "display_name": "月之暗面 Kimi k2.5 (推理/视觉/文本)" },
                { "type": "model", "id": "glm-5.1", "display_name": "智谱 GLM 5.1 (文本生成)" },
                { "type": "model", "id": "glm-5", "display_name": "智谱 GLM 5 (文本生成)" },
                { "type": "model", "id": "MiniMax-M2.5", "display_name": "MiniMax M2.5 (推理/文本)" }
              ],
              "has_more": false
            }""";

    public Mono<ServerResponse> listModels(ServerRequest request) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(MODELS_JSON);
    }
}
