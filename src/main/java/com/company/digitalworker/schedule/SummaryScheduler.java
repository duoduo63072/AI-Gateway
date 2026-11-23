package com.company.digitalworker.schedule;

import com.company.digitalworker.summary.WorkSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 定时触发日报分析：默认每日凌晨跑前一天。仅在 {@code digital-worker.enabled=true}
 * 且 {@code digital-worker.schedule.enabled=true} 时注册——关掉调度仍可走手动接口。
 * fire-and-forget：subscribe 后不阻塞，失败仅记日志。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "digital-worker",
        name = {"enabled", "schedule.enabled"}, havingValue = "true")
@RequiredArgsConstructor
public class SummaryScheduler {

    private final WorkSummaryService workSummaryService;

    /** 每日凌晨：跑前一天，做当日最终定稿。 */
    @Scheduled(cron = "${digital-worker.schedule.daily-cron}")
    public void dailyRun() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("scheduled daily summary triggered for {}", yesterday);
        workSummaryService.runDailyGuarded(yesterday, null)
                .subscribe(
                        result -> log.info("scheduled daily summary finished: {}", result),
                        err -> log.error("scheduled daily summary failed: {}", err.toString()));
    }

    /** 工作日工作时段：每小时跑"当天"，让当日新工作几小时内可见。置 intraday-cron="-" 可关。 */
    @Scheduled(cron = "${digital-worker.schedule.intraday-cron}")
    public void intradayRun() {
        LocalDate today = LocalDate.now();
        log.info("scheduled intraday summary triggered for {}", today);
        workSummaryService.runDailyGuarded(today, null)
                .subscribe(
                        result -> log.info("scheduled intraday summary finished: {}", result),
                        err -> log.error("scheduled intraday summary failed: {}", err.toString()));
    }
}
