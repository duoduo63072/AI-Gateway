package com.company.digitalworker.summary;

import com.company.digitalworker.analyzer.AnalysisResult;
import com.company.digitalworker.analyzer.LlmAnalyzer;
import com.company.digitalworker.analyzer.PromptBuilder;
import com.company.digitalworker.config.DigitalWorkerProperties;
import com.company.digitalworker.source.ConversationReader;
import com.company.digitalworker.source.EmployeeActivity;
import com.company.digitalworker.summary.entity.ProjectProgress;
import com.company.digitalworker.summary.entity.WorkSummary;
import com.company.digitalworker.summary.repository.ProjectProgressRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 编排核心：读 chat_log → 逐员工出个人摘要（Pass①，并发受限）→ 汇总当期摘要出项目进度（Pass②）。
 * 失败按员工/按窗口隔离：单个员工分析失败记 ERROR 行、不中断整批；全程响应式，不占网关 EventLoop。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkSummaryService {

    private static final String UPSERT_SUMMARY = """
            INSERT INTO work_summary
                (employee_id, client_id, period_type, period_start, period_end, conversation_count,
                 summary_text, key_tasks, model_used, prompt_tokens, completion_tokens, status, error_message, generated_at)
            VALUES
                (:employeeId, :clientId, :periodType, :periodStart, :periodEnd, :conversationCount,
                 :summaryText, :keyTasks::jsonb, :modelUsed, :pt, :ct, :status, :errorMessage, now())
            ON CONFLICT (employee_id, period_type, period_start) DO UPDATE SET
                client_id          = EXCLUDED.client_id,
                period_end         = EXCLUDED.period_end,
                conversation_count = EXCLUDED.conversation_count,
                summary_text       = EXCLUDED.summary_text,
                key_tasks          = EXCLUDED.key_tasks,
                model_used         = EXCLUDED.model_used,
                prompt_tokens      = EXCLUDED.prompt_tokens,
                completion_tokens  = EXCLUDED.completion_tokens,
                status             = EXCLUDED.status,
                error_message      = EXCLUDED.error_message,
                generated_at       = now()
            RETURNING id
            """;

    private final ConversationReader conversationReader;
    private final LlmAnalyzer llmAnalyzer;
    private final PromptBuilder promptBuilder;
    private final AnalysisResult analysisResult;
    private final ProjectProgressRepository projectProgressRepository;
    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;
    private final DigitalWorkerProperties properties;

    public static final String DAILY = "DAILY";

    /** 串行化所有自动/按需分析，避免定时与手动刷新叠加打爆上游。 */
    private final AtomicBoolean inProgress = new AtomicBoolean(false);
    /** 上次分析完成时刻，用于刷新防抖。 */
    private volatile Instant lastFinished = Instant.EPOCH;

    /**
     * 带并发锁 + 防抖的当日分析入口。定时器（每日/加密）与 pm-agent 的 refresh_data 工具都走这里。
     * 防抖窗口内刚跑过 → 跳过；已有分析在跑 → 不重复触发。
     */
    public Mono<RefreshResult> runDailyGuarded(LocalDate date, Duration debounce) {
        Instant now = Instant.now();
        if (debounce != null && !debounce.isZero()
                && Duration.between(lastFinished, now).compareTo(debounce) < 0) {
            long mins = Duration.between(lastFinished, now).toMinutes();
            return Mono.just(RefreshResult.skipped(mins));
        }
        if (!inProgress.compareAndSet(false, true)) {
            return Mono.just(RefreshResult.inProgress());
        }
        return runDaily(date)
                .map(RefreshResult::done)
                .doFinally(sig -> {
                    lastFinished = Instant.now();
                    inProgress.set(false);
                });
    }

    /**
     * 跑某一天（自然日，按服务器时区）的日报分析。调度器与手动接口都走这里，统一窗口口径。
     */
    public Mono<RunResult> runDaily(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        OffsetDateTime start = date.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        return run(DAILY, start, end);
    }

    /**
     * 跑一个分析窗口：Pass① 个人摘要 + Pass② 项目进度。返回统计供日志/接口展示。
     */
    public Mono<RunResult> run(String periodType, OffsetDateTime start, OffsetDateTime end) {
        log.info("digital-worker run start: period={} window=[{}, {})", periodType, start, end);
        int concurrency = Math.max(1, properties.getAnalysis().getConcurrency());

        return conversationReader.read(start, end)
                .flatMap(activity -> analyzeEmployee(periodType, start, end, activity), concurrency)
                .collectList()
                .flatMap(summaries -> aggregateProjects(periodType, start, end, summaries)
                        .map(projectCount -> buildResult(periodType, start, summaries, projectCount)))
                .doOnNext(r -> log.info("digital-worker run done: {}", r));
    }

    // ---------------------------------------------------------------- Pass① 个人摘要

    private Mono<WorkSummary> analyzeEmployee(String periodType, OffsetDateTime start, OffsetDateTime end,
                                             EmployeeActivity activity) {
        if (activity.conversations().isEmpty()) {
            return upsert(summary(activity, periodType, start, end, "EMPTY", null, null, null, null, null));
        }
        PromptBuilder.Prompt prompt = promptBuilder.individual(activity);
        return llmAnalyzer.analyze(prompt.system(), prompt.user())
                .map(resp -> {
                    AnalysisResult.Individual ind = analysisResult.parseIndividual(resp.text());
                    return summary(activity, periodType, start, end, "OK",
                            ind.summary(), ind.keyTasks(), resp.promptTokens(), resp.completionTokens(), null);
                })
                .onErrorResume(err -> {
                    log.warn("analyze employee {} failed: {}", activity.employeeId(), err.toString());
                    return Mono.just(summary(activity, periodType, start, end, "ERROR",
                            null, null, null, null, truncate(err.toString())));
                })
                .flatMap(this::upsert);
    }

    private WorkSummary summary(EmployeeActivity a, String periodType, OffsetDateTime start, OffsetDateTime end,
                               String status, String summaryText, JsonNode keyTasks,
                               Integer pt, Integer ct, String error) {
        return WorkSummary.builder()
                .employeeId(a.employeeId())
                .clientId(a.clientId())
                .periodType(periodType)
                .periodStart(start)
                .periodEnd(end)
                .conversationCount(a.conversations().size())
                .summaryText(summaryText)
                .keyTasks(jsonOf(keyTasks))
                .modelUsed(properties.getAnalysis().getModel())
                .promptTokens(pt)
                .completionTokens(ct)
                .status(status)
                .errorMessage(error)
                .build();
    }

    private Mono<WorkSummary> upsert(WorkSummary s) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(UPSERT_SUMMARY)
                .bind("employeeId", s.getEmployeeId())
                .bind("periodType", s.getPeriodType())
                .bind("periodStart", s.getPeriodStart())
                .bind("periodEnd", s.getPeriodEnd())
                .bind("conversationCount", s.getConversationCount())
                .bind("status", s.getStatus());
        spec = bindNullable(spec, "clientId", s.getClientId(), Long.class);
        spec = bindNullable(spec, "summaryText", s.getSummaryText(), String.class);
        spec = bindNullable(spec, "keyTasks", jsonText(s.getKeyTasks()), String.class);
        spec = bindNullable(spec, "modelUsed", s.getModelUsed(), String.class);
        spec = bindNullable(spec, "pt", s.getPromptTokens(), Integer.class);
        spec = bindNullable(spec, "ct", s.getCompletionTokens(), Integer.class);
        spec = bindNullable(spec, "errorMessage", s.getErrorMessage(), String.class);
        return spec.map((row, meta) -> {
            s.setId(row.get("id", Long.class));
            return s;
        }).one();
    }

    // ---------------------------------------------------------------- Pass② 项目进度

    private Mono<Integer> aggregateProjects(String periodType, OffsetDateTime start, OffsetDateTime end,
                                           List<WorkSummary> summaries) {
        List<WorkSummary> usable = summaries.stream()
                .filter(s -> "OK".equals(s.getStatus()))
                .filter(s -> s.getSummaryText() != null && !s.getSummaryText().isBlank())
                .toList();

        // 无论有无可用摘要，都先清掉同窗口旧的项目进度，保证重跑幂等
        Mono<Void> clear = projectProgressRepository.deleteByPeriodTypeAndPeriodStart(periodType, start);
        if (usable.isEmpty()) {
            return clear.thenReturn(0);
        }

        Json sourceIds = jsonOf(idsArray(usable));
        PromptBuilder.Prompt prompt = promptBuilder.project(usable);

        return clear.then(llmAnalyzer.analyze(prompt.system(), prompt.user()))
                .map(resp -> analysisResult.parseProjects(resp.text()))
                .flatMapMany(projects -> Flux.fromIterable(projects)
                        .map(p -> toProgress(p, periodType, start, end, sourceIds)))
                .flatMap(projectProgressRepository::save)
                .count()
                .map(Long::intValue)
                .onErrorResume(err -> {
                    log.warn("aggregate projects failed for window {}: {}", start, err.toString());
                    return Mono.just(0);
                });
    }

    private ProjectProgress toProgress(AnalysisResult.Project p, String periodType,
                                       OffsetDateTime start, OffsetDateTime end, Json sourceIds) {
        return ProjectProgress.builder()
                .periodType(periodType)
                .periodStart(start)
                .periodEnd(end)
                .projectKey(p.projectKey())
                .projectName(p.projectName())
                .summaryText(p.summary())
                .contributors(jsonOf(p.contributors()))
                .statusSignal(p.statusSignal())
                .sourceSummaryIds(sourceIds)
                .modelUsed(properties.getAnalysis().getModel())
                .generatedAt(OffsetDateTime.now())
                .build();
    }

    // ---------------------------------------------------------------- 辅助

    private RunResult buildResult(String periodType, OffsetDateTime start,
                                  List<WorkSummary> summaries, int projects) {
        long ok = summaries.stream().filter(s -> "OK".equals(s.getStatus())).count();
        long empty = summaries.stream().filter(s -> "EMPTY".equals(s.getStatus())).count();
        long error = summaries.stream().filter(s -> "ERROR".equals(s.getStatus())).count();
        return new RunResult(periodType, start, summaries.size(), (int) ok, (int) empty, (int) error, projects);
    }

    private JsonNode idsArray(List<WorkSummary> summaries) {
        var arr = objectMapper.createArrayNode();
        summaries.forEach(s -> arr.add(s.getId()));
        return arr;
    }

    private Json jsonOf(JsonNode node) {
        return node == null ? null : Json.of(node.toString());
    }

    private String jsonText(Json json) {
        return json == null ? null : json.asString();
    }

    private DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, Object value, Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    /** 一次运行的统计结果。 */
    public record RunResult(String periodType, OffsetDateTime periodStart, int employees,
                            int summariesOk, int summariesEmpty, int summariesError, int projects) {
    }

    /** 带锁/防抖的刷新结果：REFRESHED 实际跑了 / SKIPPED_RECENT 刚跑过 / IN_PROGRESS 正在跑。 */
    public record RefreshResult(String status, RunResult result, long minutesAgo) {
        static RefreshResult done(RunResult r) {
            return new RefreshResult("REFRESHED", r, 0);
        }
        static RefreshResult skipped(long minutesAgo) {
            return new RefreshResult("SKIPPED_RECENT", null, minutesAgo);
        }
        static RefreshResult inProgress() {
            return new RefreshResult("IN_PROGRESS", null, 0);
        }
    }
}
