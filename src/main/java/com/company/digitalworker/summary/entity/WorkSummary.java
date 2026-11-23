package com.company.digitalworker.summary.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

/**
 * 个人工作摘要（Pass① 产出）。一员工一分析周期一行，承载"这个人这段时间在做什么"。
 * 写入走 upsert（按 employee_id + period_type + period_start 唯一键），支持窗口重跑覆盖。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("work_summary")
public class WorkSummary {

    @Id
    private Long id;

    @Column("employee_id")
    private String employeeId;

    @Column("client_id")
    private Long clientId;

    @Column("period_type")
    private String periodType;

    @Column("period_start")
    private OffsetDateTime periodStart;

    @Column("period_end")
    private OffsetDateTime periodEnd;

    @Column("conversation_count")
    private Integer conversationCount;

    @Column("summary_text")
    private String summaryText;

    @Column("key_tasks")
    private Json keyTasks;

    @Column("model_used")
    private String modelUsed;

    @Column("prompt_tokens")
    private Integer promptTokens;

    @Column("completion_tokens")
    private Integer completionTokens;

    @Column("status")
    private String status;

    @Column("error_message")
    private String errorMessage;

    @Column("generated_at")
    private OffsetDateTime generatedAt;
}
