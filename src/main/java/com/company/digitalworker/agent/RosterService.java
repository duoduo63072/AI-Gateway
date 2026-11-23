package com.company.digitalworker.agent;

import com.company.aigateway.persistence.entity.ApiClient;
import com.company.aigateway.persistence.repository.ApiClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Comparator;

/**
 * 构建并缓存"姓名↔工号"花名册字符串，注入 pm-agent 提示词，
 * 让模型能把用户提到的姓名换算成工号、或把工号显示成姓名。
 * <b>花名册不代表权限</b>——可见范围仍由 {@link AgentScope} 在数据层强制过滤。
 * 员工增减低频，故带 TTL 内存缓存，避免每次问答都查库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RosterService {

    private static final long TTL_MS = 300_000; // 5 分钟

    private final ApiClientRepository apiClientRepository;

    private volatile String cached;
    private volatile long cachedAtMs;

    /** 返回 "张鹏 11230 / 章锦跃 12000 / ..."；失败或为空时降级为空串（提示词据此省略花名册块）。 */
    public Mono<String> roster() {
        String snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAtMs < TTL_MS) {
            return Mono.just(snapshot);
        }
        return apiClientRepository.findByEnabledIsTrue()
                .filter(c -> notBlank(c.getEmployeeId()) && notBlank(c.getDisplayName()))
                .sort(Comparator.comparing(ApiClient::getEmployeeId))
                .map(c -> c.getDisplayName() + " " + c.getEmployeeId())
                .collectList()
                .map(list -> String.join(" / ", list))
                .doOnNext(s -> {
                    cached = s;
                    cachedAtMs = System.currentTimeMillis();
                })
                .onErrorResume(e -> {
                    log.warn("build roster failed: {}", e.toString());
                    return Mono.just(snapshot == null ? "" : snapshot);
                });
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
