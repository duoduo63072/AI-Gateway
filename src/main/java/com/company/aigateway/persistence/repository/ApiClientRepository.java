package com.company.aigateway.persistence.repository;

import com.company.aigateway.persistence.entity.ApiClient;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ApiClientRepository extends ReactiveCrudRepository<ApiClient, Long> {

    /** 按 API Key 的 SHA-256 哈希查找已启用的客户端。 */
    Mono<ApiClient> findByApiKeyHashAndEnabledIsTrue(String apiKeyHash);

    /** 查所有已启用客户端，用于构建"姓名↔工号"花名册（量小、低频变化）。 */
    Flux<ApiClient> findByEnabledIsTrue();
}
