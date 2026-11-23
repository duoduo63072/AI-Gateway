package com.company.digitalworker.agent;

import com.company.aigateway.security.AuthenticatedClient;

/**
 * pm-agent 的数据可见范围，由认证身份推出。这是权限的唯一真相源——
 * 工具查询一律按它过滤，<b>绝不依赖提示词约束模型</b>（防话术诱导越权）。
 *
 * @param all         是否可见全部（领导/管理员）
 * @param employeeId  受限身份时锁定的本人工号
 * @param displayName 提问者姓名（仅用于提示词自我介绍/称呼，不参与鉴权）
 */
public record AgentScope(boolean all, String employeeId, String displayName, String role) {

    public static AgentScope from(AuthenticatedClient client) {
        if (client == null) {
            return new AgentScope(false, null, null, "EMPLOYEE"); // 兜底取最小权限
        }
        return new AgentScope(client.isPrivileged(), client.employeeId(),
                client.displayName(), client.role());
    }

    /** 受限身份查不到任何东西（无本人工号）时为 true。 */
    public boolean selfWithoutId() {
        return !all && (employeeId == null || employeeId.isBlank());
    }
}
