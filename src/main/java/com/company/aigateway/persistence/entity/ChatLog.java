package com.company.aigateway.persistence.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 会话主日志。一次 chat/completions 请求对应一行，承载完整微调语料。
 * 多构造器并存：Spring Data R2DBC 走无参构造 + setter 填充；@Builder 供应用侧构造。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("chat_log")
public class ChatLog {

    @Id
    private Long id;

    @Column("request_id")
    private UUID requestId;

    @Column("upstream_request_id")
    private String upstreamRequestId;

    @Column("trace_id")
    private String traceId;

    @Column("span_id")
    private String spanId;

    @Column("client_id")
    private Long clientId;

    @Column("employee_id")
    private String employeeId;

    @Column("model_requested")
    private String modelRequested;

    @Column("model_upstream")
    private String modelUpstream;

    @Column("is_stream")
    private Boolean isStream;

    @Column("request_messages")
    private Json requestMessages;

    @Column("request_params")
    private Json requestParams;

    @Column("completion_content")
    private String completionContent;

    @Column("completion_role")
    private String completionRole;

    @Column("tool_calls")
    private Json toolCalls;

    @Column("finish_reason")
    private String finishReason;

    @Column("prompt_tokens_est")
    private Integer promptTokensEst;

    @Column("completion_tokens_est")
    private Integer completionTokensEst;

    @Column("total_tokens_est")
    private Integer totalTokensEst;

    @Column("token_source")
    private String tokenSource;

    @Column("status")
    private String status;

    @Column("http_status")
    private Integer httpStatus;

    @Column("error_type")
    private String errorType;

    @Column("error_message")
    private String errorMessage;

    @Column("ttft_ms")
    private Integer ttftMs;

    @Column("duration_ms")
    private Integer durationMs;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("finished_at")
    private OffsetDateTime finishedAt;
}
