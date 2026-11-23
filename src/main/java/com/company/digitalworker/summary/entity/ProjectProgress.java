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
 * 项目进度（Pass② 二级聚合产出）。把当期所有个人摘要按项目/主题归类，给领导一份"各项目进展 + 谁在推"。
 * 重跑时按窗口先删后插（项目名非稳定主键，无法 upsert）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("project_progress")
public class ProjectProgress {

    @Id
    private Long id;

    @Column("period_type")
    private String periodType;

    @Column("period_start")
    private OffsetDateTime periodStart;

    @Column("period_end")
    private OffsetDateTime periodEnd;

    @Column("project_key")
    private String projectKey;

    @Column("project_name")
    private String projectName;

    @Column("summary_text")
    private String summaryText;

    @Column("contributors")
    private Json contributors;

    @Column("status_signal")
    private String statusSignal;

    @Column("source_summary_ids")
    private Json sourceSummaryIds;

    @Column("model_used")
    private String modelUsed;

    @Column("generated_at")
    private OffsetDateTime generatedAt;
}
