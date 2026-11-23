package com.company.digitalworker.summary.repository;

import com.company.digitalworker.summary.entity.ProjectProgress;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

/**
 * 项目进度读写。无稳定主键，重跑时按窗口先删后插，故提供 deleteBy 窗口方法。
 */
@Repository
public interface ProjectProgressRepository extends ReactiveCrudRepository<ProjectProgress, Long> {

    /** 按时间窗列出项目进度（报告：/api/worker/projects）。 */
    Flux<ProjectProgress> findByPeriodStartBetweenOrderByPeriodStartDesc(OffsetDateTime from, OffsetDateTime to);

    /** 重跑前清掉同窗口旧记录，保证幂等。 */
    Mono<Void> deleteByPeriodTypeAndPeriodStart(String periodType, OffsetDateTime periodStart);
}
