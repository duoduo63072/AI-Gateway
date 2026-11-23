package com.company.aigateway.persistence;

import com.company.aigateway.config.GatewayProperties;
import com.company.aigateway.persistence.entity.ChatLog;
import com.company.aigateway.persistence.repository.ChatLogRepository;
import io.r2dbc.spi.R2dbcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 异步落库服务：与流转发链路解耦，所有写入跑在 boundedElastic，绝不阻塞 EventLoop。
 * 采用 fire-and-forget，落库失败仅记日志、不影响已返回客户端的响应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapturePersistenceService {

    private final ChatLogRepository chatLogRepository;
    private final DatabaseClient databaseClient;
    private final GatewayProperties properties;

    public void persistAsync(ChatLog logEntry) {
        if (!properties.getCapture().isEnabled()) {
            return;
        }
        chatLogRepository.save(logEntry)
                .flatMap(saved -> updateDailyUsage(saved).thenReturn(saved))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        saved -> log.debug("captured chat_log id={} request_id={} status={}",
                                saved.getId(), saved.getRequestId(), saved.getStatus()),
                        err -> {
                            // 打印根因 + SQLState（而非外层 toString），便于定位偶发落库失败；不打全栈以免刷屏。
                            Throwable root = NestedExceptionUtils.getMostSpecificCause(err);
                            String sqlState = (root instanceof R2dbcException r) ? r.getSqlState() : null;
                            log.error("failed to persist chat_log request_id={} sqlState={} cause={}",
                                    logEntry.getRequestId(), sqlState, root.toString());
                        });
    }

    /** 每日 Token 用量聚合 upsert。 */
    private Mono<Void> updateDailyUsage(ChatLog l) {
        if (l.getClientId() == null) {
            return Mono.empty();
        }
        int pt = l.getPromptTokensEst() == null ? 0 : l.getPromptTokensEst();
        int ct = l.getCompletionTokensEst() == null ? 0 : l.getCompletionTokensEst();
        int tt = l.getTotalTokensEst() == null ? 0 : l.getTotalTokensEst();
        return databaseClient.sql("""
                        INSERT INTO token_usage_daily
                            (stat_date, client_id, model_upstream, request_count, prompt_tokens, completion_tokens, total_tokens)
                        VALUES (CURRENT_DATE, :clientId, :model, 1, :pt, :ct, :tt)
                        ON CONFLICT (stat_date, client_id, model_upstream) DO UPDATE SET
                            request_count     = token_usage_daily.request_count + 1,
                            prompt_tokens     = token_usage_daily.prompt_tokens + EXCLUDED.prompt_tokens,
                            completion_tokens = token_usage_daily.completion_tokens + EXCLUDED.completion_tokens,
                            total_tokens      = token_usage_daily.total_tokens + EXCLUDED.total_tokens
                        """)
                .bind("clientId", l.getClientId())
                .bind("model", l.getModelUpstream())
                .bind("pt", pt)
                .bind("ct", ct)
                .bind("tt", tt)
                .fetch()
                .rowsUpdated()
                .then();
    }
}
