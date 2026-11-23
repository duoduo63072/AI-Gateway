package com.company.aigateway.persistence.repository;

import com.company.aigateway.persistence.entity.ChatLog;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatLogRepository extends ReactiveCrudRepository<ChatLog, Long> {
}
