package com.company.digitalworker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * pm-agent 工具的取数层。所有查询都按 {@link AgentScope} 强制过滤：
 * 领导（all）可查任意员工/全部项目；员工只能查自己的摘要、以及自己参与（contributors 含本人工号）的项目。
 * 权限在 SQL 层落地，参数来自认证身份而非模型输出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmDataService {

    private static final int LIMIT = 200;

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    /** 查个人工作摘要。受限身份强制锁定本人工号；领导可传 employeeId 精确查某人，留空查全部。 */
    public Mono<JsonNode> queryWorkSummaries(AgentScope scope, String requestedEmployeeId,
                                             OffsetDateTime from, OffsetDateTime to) {
        if (scope.selfWithoutId()) {
            return Mono.just(objectMapper.createArrayNode());
        }
        String employeeId = scope.all() ? trimToNull(requestedEmployeeId) : scope.employeeId();

        StringBuilder sql = new StringBuilder("""
                SELECT w.employee_id, c.display_name, w.period_start, w.status,
                       w.summary_text, w.key_tasks::text AS key_tasks
                FROM work_summary w
                LEFT JOIN api_client c ON c.employee_id = w.employee_id
                WHERE w.period_start >= :from AND w.period_start < :to
                """);
        if (employeeId != null) {
            sql.append(" AND w.employee_id = :emp");
        }
        sql.append(" ORDER BY w.period_start DESC, w.employee_id LIMIT ").append(LIMIT);

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString())
                .bind("from", from).bind("to", to);
        if (employeeId != null) {
            spec = spec.bind("emp", employeeId);
        }
        return spec.map((row, meta) -> summaryRow(row)).all().collectList().map(this::toArray);
    }

    /** 查项目进度。受限身份只返回 contributors 含本人工号的项目；领导查全部。 */
    public Mono<JsonNode> queryProjectProgress(AgentScope scope, OffsetDateTime from, OffsetDateTime to,
                                               String projectKey) {
        if (scope.selfWithoutId()) {
            return Mono.just(objectMapper.createArrayNode());
        }
        StringBuilder sql = new StringBuilder("""
                SELECT period_start, project_name, project_key, status_signal,
                       summary_text, contributors::text AS contributors
                FROM project_progress
                WHERE period_start >= :from AND period_start < :to
                """);
        boolean self = !scope.all();
        if (self) {
            sql.append(" AND contributors @> :empJson::jsonb");
        }
        String pk = trimToNull(projectKey);
        if (pk != null) {
            sql.append(" AND project_key = :pk");
        }
        sql.append(" ORDER BY period_start DESC LIMIT ").append(LIMIT);

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString())
                .bind("from", from).bind("to", to);
        if (self) {
            spec = spec.bind("empJson", "[\"" + scope.employeeId() + "\"]");
        }
        if (pk != null) {
            spec = spec.bind("pk", pk);
        }
        return spec.map((row, meta) -> projectRow(row)).all().collectList().map(this::toArray);
    }

    /**
     * 查 AI 使用量与成本（按员工聚合，给定日期窗 [from, toExclusive)）。
     * 领导可查全部或指定员工；员工只能查自己。成本 = Σ各模型(prompt×输入价 + completion×输出价)/1000，
     * 口径与 QuotaService / v_employee_monthly_cost 一致；未配价的模型按 0 计。
     */
    public Mono<JsonNode> queryAiUsageCost(AgentScope scope, String requestedEmployeeId,
                                           LocalDate from, LocalDate toExclusive) {
        if (scope.selfWithoutId()) {
            return Mono.just(objectMapper.createArrayNode());
        }
        String employeeId = scope.all() ? trimToNull(requestedEmployeeId) : scope.employeeId();

        StringBuilder sql = new StringBuilder("""
                SELECT c.employee_id, c.display_name, c.monthly_quota_cny,
                       COALESCE(SUM(d.request_count), 0)     AS requests,
                       COALESCE(SUM(d.prompt_tokens), 0)     AS prompt_tokens,
                       COALESCE(SUM(d.completion_tokens), 0) AS completion_tokens,
                       COALESCE(SUM(d.total_tokens), 0)      AS total_tokens,
                       COALESCE(SUM(
                           (d.prompt_tokens     * COALESCE(p.input_price_per_1k,  def.input_price_per_1k,  0)
                          + d.completion_tokens * COALESCE(p.output_price_per_1k, def.output_price_per_1k, 0)) / 1000.0
                       ), 0) AS cost_cny
                FROM api_client c
                LEFT JOIN token_usage_daily d
                       ON d.client_id = c.id
                      AND d.stat_date >= :from AND d.stat_date < :to
                LEFT JOIN model_pricing p   ON p.model_upstream   = d.model_upstream
                LEFT JOIN model_pricing def ON def.model_upstream = '__default__'
                WHERE c.employee_id IS NOT NULL
                """);
        if (employeeId != null) {
            sql.append(" AND c.employee_id = :emp");
        }
        sql.append(" GROUP BY c.employee_id, c.display_name, c.monthly_quota_cny");
        sql.append(" ORDER BY cost_cny DESC LIMIT ").append(LIMIT);

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString())
                .bind("from", from).bind("to", toExclusive);
        if (employeeId != null) {
            spec = spec.bind("emp", employeeId);
        }
        return spec.map((row, meta) -> usageRow(row)).all().collectList().map(this::toArray);
    }

    // ---------------------------------------------------------------- 行映射

    private ObjectNode usageRow(Readable row) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("employeeId", row.get("employee_id", String.class));
        n.put("name", row.get("display_name", String.class));
        n.put("requests", num(row, "requests"));
        n.put("promptTokens", num(row, "prompt_tokens"));
        n.put("completionTokens", num(row, "completion_tokens"));
        n.put("totalTokens", num(row, "total_tokens"));
        n.put("costCny", round2(dbl(row, "cost_cny")));
        Object quota = row.get("monthly_quota_cny");
        if (quota != null) {
            n.put("quotaCny", round2(((Number) quota).doubleValue()));
        }
        return n;
    }

    private long num(Readable row, String col) {
        Object v = row.get(col);
        return v == null ? 0L : ((Number) v).longValue();
    }

    private double dbl(Readable row, String col) {
        Object v = row.get(col);
        return v == null ? 0.0 : ((Number) v).doubleValue();
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private ObjectNode summaryRow(Readable row) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("employeeId", row.get("employee_id", String.class));
        n.put("name", row.get("display_name", String.class));
        n.put("date", dateOf(row.get("period_start", OffsetDateTime.class)));
        n.put("status", row.get("status", String.class));
        n.put("summary", row.get("summary_text", String.class));
        n.set("keyTasks", parseJson(row.get("key_tasks", String.class)));
        return n;
    }

    private ObjectNode projectRow(Readable row) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("date", dateOf(row.get("period_start", OffsetDateTime.class)));
        n.put("projectName", row.get("project_name", String.class));
        n.put("projectKey", row.get("project_key", String.class));
        n.put("statusSignal", row.get("status_signal", String.class));
        n.put("summary", row.get("summary_text", String.class));
        n.set("contributors", parseJson(row.get("contributors", String.class)));
        return n;
    }

    private JsonNode toArray(List<ObjectNode> rows) {
        ArrayNode arr = objectMapper.createArrayNode();
        rows.forEach(arr::add);
        return arr;
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String dateOf(OffsetDateTime t) {
        return t == null ? null : t.toLocalDate().toString();
    }

    private String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
