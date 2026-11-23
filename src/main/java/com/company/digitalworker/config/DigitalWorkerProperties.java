package com.company.digitalworker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟员工（PM 数字员工）模块配置，前缀 {@code digital-worker}。
 *
 * <p>{@code enabled} 是模块总开关，默认 {@code false}——不开启时定时器与报告接口都不注册，
 * 对网关零影响。模块只读 {@code chat_log}、调百炼上游出"个人工作摘要 + 项目进度"。
 *
 * <p>⚠️ 注意：摘要源自员工的 AI 对话，属敏感数据。开启前请确认已就访问控制/合规与团队达成一致。
 */
@Data
@ConfigurationProperties(prefix = "digital-worker")
public class DigitalWorkerProperties {

    /** 模块总开关。false 时定时分析与 /api/worker 报告接口均不注册（默认关，避免打扰网关）。 */
    private boolean enabled = false;

    private Schedule schedule = new Schedule();
    private Analysis analysis = new Analysis();
    private Agent agent = new Agent();

    @Data
    public static class Schedule {
        /** 是否启用定时自动分析。模块开启时，置 false 可只保留手动触发与报告接口。 */
        private boolean enabled = true;
        /** 每日分析触发的 cron（默认凌晨 02:00 跑前一天，做当日最终定稿）。 */
        private String dailyCron = "0 0 2 * * *";
        /** 工作日工作时段的加密分析 cron（默认 10:00–18:00 每小时整点，分析"当天"以保持新鲜）。置 "-" 可关闭。 */
        private String intradayCron = "0 0 10-18 * * MON-FRI";
    }

    @Data
    public static class Analysis {
        /** 分析所用上游模型（总结质量优先，默认走强模型）。 */
        private String model = "qwen3.7-max";
        /** 单员工单期最多取多少条对话作为分析原料（控上下文与成本）。 */
        private int maxConversationsPerEmployee = 40;
        /** 单条对话用户输入/回答各自截断到的最大字符数。 */
        private int maxCharsPerConversation = 1200;
        /** 逐员工分析的并发上限（避免一次性打爆上游）。 */
        private int concurrency = 3;
        /** LLM 单次响应的 max_tokens。 */
        private int maxTokens = 1500;
        /** 单次 LLM 调用硬超时。上游卡住时 fail-fast，避免拖垮整轮分析（netty idle-read 兜不住挂起场景）。 */
        private Duration timeout = Duration.ofSeconds(90);
    }

    /** pm-agent 对话式数字员工配置。 */
    @Data
    public static class Agent {
        /** 命中这些 model 名的 /v1/messages 请求路由到 pm-agent（而非透传上游）。 */
        private List<String> modelNames = new ArrayList<>(List.of("pm-agent"));
        /** agent 自身推理 + 工具调用所用上游模型。 */
        private String model = "qwen3.7-max";
        /** agent 单次上游响应的 max_tokens。 */
        private int maxTokens = 1500;
        /** 工具调用循环的最大轮数（防止无限 tool_use）。 */
        private int maxToolIterations = 4;
        /** 未指定日期时，默认回看的天数。 */
        private int defaultLookbackDays = 7;
        /** 单次上游调用硬超时。 */
        private Duration timeout = Duration.ofSeconds(90);
        /** refresh_data 工具的防抖窗口：此时间内刚分析过则跳过、直接告知已是最新。 */
        private Duration refreshDebounce = Duration.ofMinutes(10);
        /** 对 Claude Code 的流式请求是否做"真流式"（每轮流式调上游、token 实时透传）。false 回退为整段合成 SSE。 */
        private boolean streamEnabled = true;
    }
}
