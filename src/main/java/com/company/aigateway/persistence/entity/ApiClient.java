package com.company.aigateway.persistence.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 客户端/员工身份。网关 API Key 以 SHA-256 存储，绝不存明文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("api_client")
public class ApiClient {

    @Id
    private Long id;

    @Column("client_key_id")
    private String clientKeyId;

    @Column("api_key_hash")
    private String apiKeyHash;

    @Column("employee_id")
    private String employeeId;

    @Column("display_name")
    private String displayName;

    @Column("enabled")
    private Boolean enabled;

    @Column("rate_limit_qps")
    private Integer rateLimitQps;

    @Column("role")
    private String role;

    /** 每月成本额度(人民币元)；null 表示不限额。 */
    @Column("monthly_quota_cny")
    private BigDecimal monthlyQuotaCny;

    @Column("created_at")
    private OffsetDateTime createdAt;
}
