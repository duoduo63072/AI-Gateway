package com.company.aigateway.quota;

import com.company.aigateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 员工成本额度服务：把 token 用量按 {@code model_pricing} 折算成「人民币元」，
 * 与 {@code api_client.monthly_quota_cny} 比对，判断是否超额。
 *
 * <p>采用「软封顶」：不拦截请求，仅在超额且为会话第一轮时，向调用方返回一段告警文案，
 * 由代理层注入到回答末尾。本月成本带内存缓存(默认 60s)，避免每请求查库。
 *
 * <p>全程 fail-open：任何异常都只是「不告警」，绝不影响正常转发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    /**
     * 本月成本 = Σ各模型(prompt_tokens×输入价 + completion_tokens×输出价) / 1000；同时取该员工额度。
     * 单价优先取该模型的专属行(p)，没有则回退到兜底行 '__default__'(def)，再没有按 0。
     */
    private static final String MONTH_COST_SQL = """
            SELECT ac.monthly_quota_cny AS quota,
                   COALESCE(SUM(
                       (d.prompt_tokens     * COALESCE(p.input_price_per_1k,  def.input_price_per_1k,  0)
                      + d.completion_tokens * COALESCE(p.output_price_per_1k, def.output_price_per_1k, 0)) / 1000.0
                   ), 0) AS month_cost
            FROM api_client ac
            LEFT JOIN token_usage_daily d
                   ON d.client_id = ac.id
                  AND d.stat_date >= date_trunc('month', CURRENT_DATE)::date
            LEFT JOIN model_pricing p   ON p.model_upstream   = d.model_upstream
            LEFT JOIN model_pricing def ON def.model_upstream = '__default__'
            WHERE ac.id = :clientId
            GROUP BY ac.monthly_quota_cny
            """;

    private final DatabaseClient databaseClient;
    private final GatewayProperties properties;

    /** clientId -> 本月成本快照(带时间戳)，简单 TTL 缓存。 */
    private final ConcurrentMap<Long, Snapshot> cache = new ConcurrentHashMap<>();

    /**
     * 返回需要注入到回答末尾的告警文案；无需告警(未启用/未超额/非会话首轮/出错)时返回 {@link Mono#empty()}。
     *
     * @param clientId    已鉴权客户端的主键；null(如主密钥合成身份) 直接跳过
     * @param requestBody 原始请求体，用于判定是否为会话第一轮
     */
    public Mono<String> warningFor(Long clientId, JsonNode requestBody) {
        GatewayProperties.Quota cfg = properties.getQuota();
        if (!cfg.isEnabled() || clientId == null || !isFirstTurn(requestBody)) {
            return Mono.empty();
        }
        return snapshot(clientId)
                .flatMap(s -> {
                    if (s.quota() == null || s.cost().compareTo(s.quota()) < 0) {
                        return Mono.empty();
                    }
                    return Mono.just(formatWarning(s.cost(), s.quota()));
                })
                .onErrorResume(e -> {
                    log.warn("quota check failed for clientId={}, skip warning: {}", clientId, e.toString());
                    return Mono.empty();
                });
    }

    private Mono<Snapshot> snapshot(Long clientId) {
        long ttlMs = Math.max(0, properties.getQuota().getCacheTtlSeconds()) * 1000L;
        Snapshot cached = cache.get(clientId);
        if (cached != null && (System.currentTimeMillis() - cached.atMillis()) < ttlMs) {
            return Mono.just(cached);
        }
        return databaseClient.sql(MONTH_COST_SQL)
                .bind("clientId", clientId)
                .map((row, meta) -> new Snapshot(
                        nz(row.get("month_cost", BigDecimal.class)),
                        row.get("quota", BigDecimal.class), // 可能为 null = 不限额
                        System.currentTimeMillis()))
                .one()
                .defaultIfEmpty(new Snapshot(BigDecimal.ZERO, null, System.currentTimeMillis()))
                .doOnNext(s -> cache.put(clientId, s));
    }

    private String formatWarning(BigDecimal cost, BigDecimal quota) {
        return properties.getQuota().getWarningTemplate()
                .replace("{month}", YearMonth.now().toString())
                .replace("{cost}", cost.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .replace("{quota}", quota.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    /**
     * 会话「第一轮」判定：messages 中尚无 assistant 角色消息。
     * 这样既能识别 Anthropic(system 独立字段) 也能识别 OpenAI(system 在 messages 内) 格式，
     * 且工具调用循环/多轮对话(含 assistant 消息)不会重复触发 —— 天然实现「每会话一次」。
     */
    public static boolean isFirstTurn(JsonNode requestBody) {
        JsonNode messages = requestBody == null ? null : requestBody.get("messages");
        if (messages == null || !messages.isArray()) {
            return false; // 形态不明，保守不注入
        }
        for (JsonNode m : messages) {
            if ("assistant".equals(m.path("role").asText(""))) {
                return false;
            }
        }
        return true;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 本月成本快照：cost 不为 null；quota 为 null 表示不限额。 */
    private record Snapshot(BigDecimal cost, BigDecimal quota, long atMillis) {
    }
}
