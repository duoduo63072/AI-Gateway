package com.company.digitalworker.source;

import com.company.digitalworker.config.DigitalWorkerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读消费侧：按时间窗从 {@code chat_log} 取成功对话，按员工分组并裁剪成分析原料。
 *
 * <p>只取 {@code status='SUCCESS'} 且有最终回答、且 employee_id 非空的行（匿名/主密钥流量不计入个人摘要）。
 * 裁剪策略：每条对话只留"最后一轮用户输入 + 最终文本回答"，各自截断到配置上限；每员工至多保留最近 N 条。
 * 网关侧只写 chat_log，这里只读——两边唯一接缝就是这张表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationReader {

    private static final String SQL = """
            SELECT employee_id, client_id, model_upstream, created_at,
                   request_messages::text AS request_messages, completion_content
            FROM chat_log
            WHERE created_at >= :start AND created_at < :end
              AND status = 'SUCCESS'
              AND employee_id IS NOT NULL
              AND completion_content IS NOT NULL
            ORDER BY employee_id, created_at
            """;

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;
    private final DigitalWorkerProperties properties;

    /** 读取 [start, end) 窗口内的对话，按员工分组成 EmployeeActivity。 */
    public Flux<EmployeeActivity> read(OffsetDateTime start, OffsetDateTime end) {
        return databaseClient.sql(SQL)
                .bind("start", start)
                .bind("end", end)
                .map((row, meta) -> new Raw(
                        row.get("employee_id", String.class),
                        row.get("client_id", Long.class),
                        row.get("model_upstream", String.class),
                        row.get("created_at", OffsetDateTime.class),
                        row.get("request_messages", String.class),
                        row.get("completion_content", String.class)))
                .all()
                .collectList()
                .flatMapMany(rows -> Flux.fromIterable(group(rows)));
    }

    /** 按员工分组（保留出现顺序），各组裁剪到上限。 */
    private List<EmployeeActivity> group(List<Raw> rows) {
        int maxChars = properties.getAnalysis().getMaxCharsPerConversation();
        int maxConv = properties.getAnalysis().getMaxConversationsPerEmployee();

        Map<String, List<EmployeeActivity.Conversation>> byEmployee = new LinkedHashMap<>();
        Map<String, Long> clientIds = new LinkedHashMap<>();

        for (Raw r : rows) {
            String userText = truncate(extractLastUserText(r.requestMessages()), maxChars);
            String assistantText = truncate(r.completionContent(), maxChars);
            byEmployee.computeIfAbsent(r.employeeId(), k -> new ArrayList<>())
                    .add(new EmployeeActivity.Conversation(r.createdAt(), r.modelUpstream(), userText, assistantText));
            clientIds.putIfAbsent(r.employeeId(), r.clientId());
        }

        List<EmployeeActivity> activities = new ArrayList<>(byEmployee.size());
        for (var entry : byEmployee.entrySet()) {
            List<EmployeeActivity.Conversation> convs = entry.getValue();
            if (convs.size() > maxConv) {
                // 保留最近 N 条（列表按时间升序，取尾部）
                convs = new ArrayList<>(convs.subList(convs.size() - maxConv, convs.size()));
            }
            activities.add(new EmployeeActivity(entry.getKey(), clientIds.get(entry.getKey()), convs));
        }
        return activities;
    }

    /**
     * 从 request_messages 数组里取最后一条 user 消息的文本。
     * content 可能是字符串，或 Anthropic 的内容块数组（拼接其中 text 块）。
     */
    private String extractLastUserText(String requestMessagesJson) {
        if (requestMessagesJson == null || requestMessagesJson.isBlank()) {
            return "";
        }
        try {
            JsonNode messages = objectMapper.readTree(requestMessagesJson);
            if (!messages.isArray()) {
                return "";
            }
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode msg = messages.get(i);
                if (!"user".equals(msg.path("role").asText(""))) {
                    continue;
                }
                return contentToText(msg.path("content")).trim();
            }
        } catch (Exception e) {
            log.debug("parse request_messages failed: {}", e.toString());
        }
        return "";
    }

    private String contentToText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText(""))) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(block.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }

    /** 行原料（JSONB 以 ::text 取出后由本类解析）。 */
    private record Raw(
            String employeeId,
            Long clientId,
            String modelUpstream,
            OffsetDateTime createdAt,
            String requestMessages,
            String completionContent) {
    }
}
