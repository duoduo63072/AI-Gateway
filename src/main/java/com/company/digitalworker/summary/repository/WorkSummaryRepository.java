package com.company.digitalworker.summary.repository;

import com.company.digitalworker.summary.entity.WorkSummary;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;

/**
 * 个人工作摘要读取。写入用 upsert（见 WorkSummaryService 的 DatabaseClient 路径），这里只承载查询。
 */
@Repository
public interface WorkSummaryRepository extends ReactiveCrudRepository<WorkSummary, Long> {

    /** 按时间窗列出所有员工的摘要（报告：/api/worker/summaries）。 */
    Flux<WorkSummary> findByPeriodStartBetweenOrderByPeriodStartDesc(OffsetDateTime from, OffsetDateTime to);

    /** 按员工 + 时间窗列出摘要。 */
    Flux<WorkSummary> findByEmployeeIdAndPeriodStartBetweenOrderByPeriodStartDesc(
            String employeeId, OffsetDateTime from, OffsetDateTime to);

    /** 取某一具体窗口的全部摘要（Pass② 二级聚合的输入）。 */
    Flux<WorkSummary> findByPeriodTypeAndPeriodStart(String periodType, OffsetDateTime periodStart);
}
