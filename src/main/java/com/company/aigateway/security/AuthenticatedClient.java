package com.company.aigateway.security;

/**
 * 鉴权通过后的客户端身份。clientId 为 null 表示通过引导主密钥放行的合成身份。
 * role 决定数字员工可见范围：EMPLOYEE 仅自己，LEADER/ADMIN 可看全部。
 */
public record AuthenticatedClient(Long clientId, String clientKeyId, String employeeId,
                                  String displayName, String role) {

    public boolean isPrivileged() {
        return "LEADER".equals(role) || "ADMIN".equals(role);
    }
}
