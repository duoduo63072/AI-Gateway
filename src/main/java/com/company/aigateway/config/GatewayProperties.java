package com.company.aigateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 网关行为配置：鉴权、语料采集、模型名映射。
 */
@Data
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private Auth auth = new Auth();
    private Capture capture = new Capture();
    private ModelMapping modelMapping = new ModelMapping();
    private Quota quota = new Quota();

    @Data
    public static class Auth {
        /** 是否启用网关侧 API Key 鉴权。 */
        private boolean enabled = true;
        /** 可选的引导用主密钥；命中则放行为合成客户端，便于无 DB 快速启动。生产建议留空，走 DB 管理的 Key。 */
        private String masterKey;
    }

    @Data
    public static class Capture {
        /** 是否异步采集完整对话语料。 */
        private boolean enabled = true;
        /** 单条 completion 累加上限（字符），超限只落 prompt + 截断标记，防内存击穿。 */
        private int maxCompletionChars = 1_000_000;
    }

    @Data
    public static class ModelMapping {
        /** 未命中映射时使用的默认上游模型。 */
        private String defaultModel = "qwen-plus";
        /** 客户端请求模型名 -> 百炼上游模型名。 */
        private Map<String, String> mappings = new HashMap<>();
    }

    @Data
    public static class Quota {
        /** 是否启用按员工成本额度的「超额对话内告警」。关掉则完全不查额度、零开销。 */
        private boolean enabled = true;
        /** 本月成本的内存缓存有效期(秒)；规模小，默认 60s 足够，避免每请求都查库。 */
        private long cacheTtlSeconds = 60;
        /**
         * 超额告警文案，注入到回答末尾。占位符：{month}=年月、{cost}=本月成本、{quota}=额度。
         * 前置换行用于和正文答案分隔。
         */
        private String warningTemplate =
                "\n\n---\n⚠️ **用量提醒**：你本月（{month}）AI 用量成本约 ¥{cost}，"
                + "已达到/超过 ¥{quota} 的额度上限。请知悉并合理使用，如需调整额度请联系管理员。";
    }
}
