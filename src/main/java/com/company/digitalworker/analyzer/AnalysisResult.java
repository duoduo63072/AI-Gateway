package com.company.digitalworker.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 LLM 返回的文本为结构化结果。模型偶尔会把 JSON 包在 ```json``` 代码块里或带前后缀文字，
 * 这里做容错抽取；解析失败则降级——个人摘要把原文当 summary，项目归类把原文塞进一个"未归类"条目，
 * 保证流程不因模型输出格式抖动而中断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisResult {

    private final ObjectMapper objectMapper;

    /** 解析个人工作摘要。 */
    public Individual parseIndividual(String text) {
        String json = extractJson(text, '{', '}');
        if (json != null) {
            try {
                JsonNode node = objectMapper.readTree(json);
                String summary = node.path("summary").asText("").trim();
                JsonNode keyTasks = node.has("key_tasks") && node.get("key_tasks").isArray()
                        ? node.get("key_tasks") : null;
                if (!summary.isEmpty()) {
                    return new Individual(summary, keyTasks);
                }
            } catch (Exception e) {
                log.debug("parse individual JSON failed, fallback to raw: {}", e.toString());
            }
        }
        return new Individual(safe(text), null); // 降级：原文作摘要
    }

    /** 解析项目进度数组。 */
    public List<Project> parseProjects(String text) {
        String json = extractJson(text, '[', ']');
        List<Project> result = new ArrayList<>();
        if (json != null) {
            try {
                JsonNode arr = objectMapper.readTree(json);
                if (arr.isArray()) {
                    for (JsonNode node : arr) {
                        String name = node.path("project_name").asText("").trim();
                        if (name.isEmpty()) {
                            continue;
                        }
                        result.add(new Project(
                                name,
                                emptyToNull(node.path("project_key").asText("").trim()),
                                node.path("summary").asText("").trim(),
                                node.has("contributors") && node.get("contributors").isArray()
                                        ? node.get("contributors") : null,
                                emptyToNull(node.path("status_signal").asText("").trim())));
                    }
                }
            } catch (Exception e) {
                log.debug("parse projects JSON failed, fallback to raw: {}", e.toString());
            }
        }
        if (result.isEmpty()) {
            result.add(new Project("未归类", "uncategorized", safe(text), null, null)); // 降级
        }
        return result;
    }

    /** 抽取首个 open..last close 之间的 JSON 片段；找不到返回 null。 */
    private String extractJson(String text, char open, char close) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf(open);
        int end = text.lastIndexOf(close);
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private String safe(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > 4000 ? t.substring(0, 4000) : t;
    }

    private String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** 个人摘要解析结果。keyTasks 为 JSON 数组节点或 null。 */
    public record Individual(String summary, JsonNode keyTasks) {
    }

    /** 项目进度解析结果。contributors 为 JSON 数组节点或 null。 */
    public record Project(String projectName, String projectKey, String summary,
                          JsonNode contributors, String statusSignal) {
    }
}
