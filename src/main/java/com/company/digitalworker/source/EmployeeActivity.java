package com.company.digitalworker.source;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 单个员工在一个分析窗口内的对话原料（已裁剪），作为 LLM 个人摘要分析的输入。
 *
 * @param employeeId    员工工号
 * @param clientId      关联 api_client.id（可能为 null）
 * @param conversations 该员工本期的对话摘录（按时间升序，已按上限裁剪）
 */
public record EmployeeActivity(
        String employeeId,
        Long clientId,
        List<Conversation> conversations) {

    /**
     * 一轮对话的摘录：用户最后一轮输入 + 助手最终回答（均已截断）。
     */
    public record Conversation(
            OffsetDateTime at,
            String model,
            String userText,
            String assistantText) {
    }
}
