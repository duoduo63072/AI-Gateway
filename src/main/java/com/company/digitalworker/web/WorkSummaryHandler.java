package com.company.digitalworker.web;

import com.company.aigateway.security.ApiKeyAuthFilter;
import com.company.aigateway.security.AuthenticatedClient;
import com.company.digitalworker.summary.WorkSummaryService;
import com.company.digitalworker.summary.entity.ProjectProgress;
import com.company.digitalworker.summary.entity.WorkSummary;
import com.company.digitalworker.summary.repository.ProjectProgressRepository;
import com.company.digitalworker.summary.repository.WorkSummaryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * 虚拟员工报告接口的处理逻辑。/api/worker/** 仅放行网关主密钥（GATEWAY_MASTER_KEY）身份——
 * 报告含敏感的员工工作画像，只给管理员看，不对普通员工 Key 开放。
 */
@Component
@ConditionalOnProperty(prefix = "digital-worker", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class WorkSummaryHandler {

    private static final String MASTER = "master";
    private static final String FORBIDDEN_BODY =
            "{\"error\":{\"message\":\"digital-worker reports require the gateway master key\",\"code\":\"forbidden\"}}";

    private final WorkSummaryRepository workSummaryRepository;
    private final ProjectProgressRepository projectProgressRepository;
    private final WorkSummaryService workSummaryService;
    private final ObjectMapper objectMapper;

    /** GET /api/worker/summaries?employeeId=&from=&to= */
    public Mono<ServerResponse> summaries(ServerRequest request) {
        return guarded(request, () -> {
            OffsetDateTime from = startOf(param(request, "from", LocalDate.now().minusDays(7)));
            OffsetDateTime to = endOf(param(request, "to", LocalDate.now()));
            String employeeId = request.queryParam("employeeId").filter(s -> !s.isBlank()).orElse(null);

            var flux = (employeeId == null)
                    ? workSummaryRepository.findByPeriodStartBetweenOrderByPeriodStartDesc(from, to)
                    : workSummaryRepository.findByEmployeeIdAndPeriodStartBetweenOrderByPeriodStartDesc(
                            employeeId, from, to);

            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(flux.map(this::toView), SummaryView.class);
        });
    }

    /** GET /api/worker/projects?from=&to= */
    public Mono<ServerResponse> projects(ServerRequest request) {
        return guarded(request, () -> {
            OffsetDateTime from = startOf(param(request, "from", LocalDate.now().minusDays(7)));
            OffsetDateTime to = endOf(param(request, "to", LocalDate.now()));
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(projectProgressRepository.findByPeriodStartBetweenOrderByPeriodStartDesc(from, to)
                            .map(this::toView), ProjectView.class);
        });
    }

    /** POST /api/worker/run?date=YYYY-MM-DD（默认昨天）：手动触发一次日报分析。 */
    public Mono<ServerResponse> run(ServerRequest request) {
        return guarded(request, () -> {
            LocalDate date = param(request, "date", LocalDate.now().minusDays(1));
            return workSummaryService.runDaily(date)
                    .flatMap(result -> ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON).bodyValue(result));
        });
    }

    // ---------------------------------------------------------------- 鉴权 / 工具

    private Mono<ServerResponse> guarded(ServerRequest request, Supplier<Mono<ServerResponse>> action) {
        AuthenticatedClient client = (AuthenticatedClient) request.exchange()
                .getAttributes().get(ApiKeyAuthFilter.CLIENT_ATTRIBUTE);
        if (client == null || !MASTER.equals(client.clientKeyId())) {
            return ServerResponse.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(FORBIDDEN_BODY);
        }
        return action.get();
    }

    private LocalDate param(ServerRequest request, String name, LocalDate fallback) {
        return request.queryParam(name).filter(s -> !s.isBlank())
                .map(LocalDate::parse).orElse(fallback);
    }

    private OffsetDateTime startOf(LocalDate date) {
        return date.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private OffsetDateTime endOf(LocalDate date) {
        // 含当天：取次日 00:00 作为开区间上界
        return date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private JsonNode toNode(Json json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json.asString());
        } catch (Exception e) {
            return null;
        }
    }

    private SummaryView toView(WorkSummary s) {
        return new SummaryView(s.getId(), s.getEmployeeId(), s.getClientId(),
                s.getPeriodType(), s.getPeriodStart(), s.getPeriodEnd(), s.getConversationCount(),
                s.getSummaryText(), toNode(s.getKeyTasks()), s.getModelUsed(),
                s.getPromptTokens(), s.getCompletionTokens(), s.getStatus(), s.getGeneratedAt());
    }

    private ProjectView toView(ProjectProgress p) {
        return new ProjectView(p.getId(), p.getPeriodType(), p.getPeriodStart(), p.getPeriodEnd(),
                p.getProjectKey(), p.getProjectName(), p.getSummaryText(), toNode(p.getContributors()),
                p.getStatusSignal(), toNode(p.getSourceSummaryIds()), p.getModelUsed(), p.getGeneratedAt());
    }

    /** 个人摘要对外视图（key_tasks 还原为 JSON）。 */
    public record SummaryView(Long id, String employeeId, Long clientId, String periodType,
                              OffsetDateTime periodStart, OffsetDateTime periodEnd, Integer conversationCount,
                              String summaryText, JsonNode keyTasks, String modelUsed,
                              Integer promptTokens, Integer completionTokens, String status,
                              OffsetDateTime generatedAt) {
    }

    /** 项目进度对外视图。 */
    public record ProjectView(Long id, String periodType, OffsetDateTime periodStart, OffsetDateTime periodEnd,
                              String projectKey, String projectName, String summaryText, JsonNode contributors,
                              String statusSignal, JsonNode sourceSummaryIds, String modelUsed,
                              OffsetDateTime generatedAt) {
    }
}
