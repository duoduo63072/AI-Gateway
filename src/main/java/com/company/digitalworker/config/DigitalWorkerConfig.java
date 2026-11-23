package com.company.digitalworker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 虚拟员工模块的装配开关：仅当 {@code digital-worker.enabled=true} 时启用 Spring 调度基础设施。
 * 把 {@link EnableScheduling} 收在模块内，网关主类无需感知调度这件事——保持职责隔离。
 */
@Configuration
@ConditionalOnProperty(prefix = "digital-worker", name = "enabled", havingValue = "true")
@EnableScheduling
public class DigitalWorkerConfig {
}
