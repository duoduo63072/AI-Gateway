package com.company.digitalworker.analyzer;

import com.company.digitalworker.source.EmployeeActivity;
import com.company.digitalworker.summary.entity.WorkSummary;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 拼两类分析提示词：Pass① 个人工作摘要、Pass② 项目进度归类。
 * 都要求模型输出 JSON，便于结构化落库；解析见 {@link AnalysisResult}。
 */
@Component
public class PromptBuilder {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final String INDIVIDUAL_SYSTEM = """
            你是企业项目经理的助理。下面是某位员工在一段时间内通过 AI 编程助手（Claude Code）产生的对话记录，
            每条含他向助手提出的问题/诉求与助手的回答。请据此判断这位员工这段时间「实际在做什么工作」。
            要求：
            1. 用中文，客观、具体，聚焦工作内容（在做的功能/模块、排查的问题、产出），不要复述对话原文。
            2. 识别并列出关键任务与可能的卡点（如反复排查同一问题、依赖外部未决项）。
            3. 只输出一个 JSON 对象，不要任何额外文字或 markdown 代码块，格式：
               {"summary": "一段话概述此人这段时间的工作", "key_tasks": ["要点1", "要点2"]}
            若对话信息过少不足以判断，summary 给出"信息不足，仅零星操作"，key_tasks 为空数组。
            """;

    private static final String PROJECT_SYSTEM = """
            你是企业项目经理的助理。下面是同一时间窗内、多位员工各自的工作摘要。
            请站在项目视角，把这些工作归类到具体的项目/主题下，给领导一份「各项目进展 + 谁在推进」的汇总，
            替代项目例会与周报。要求：
            1. 用中文。按项目/主题聚合（同一项目的多人工作合并），给出该项目本期进展。
            2. 标出每个项目的参与者（员工工号）。判断进展信号：顺利 / 有阻塞 / 停滞。
            3. 只输出一个 JSON 数组，不要任何额外文字或 markdown 代码块，格式：
               [{"project_name":"项目或主题名","project_key":"短标识(英文小写/连字符)","summary":"本期进展","contributors":["工号1","工号2"],"status_signal":"顺利|有阻塞|停滞"}]
            无法归入明确项目的零散工作，可归到一个 project_name="其他/日常" 的条目下。
            """;

    /** Pass①：单员工对话 → 个人工作摘要提示词。 */
    public Prompt individual(EmployeeActivity activity) {
        StringBuilder sb = new StringBuilder();
        sb.append("员工工号：").append(activity.employeeId()).append('\n');
        sb.append("对话条数：").append(activity.conversations().size()).append("\n\n");
        int i = 1;
        for (EmployeeActivity.Conversation c : activity.conversations()) {
            sb.append("【对话 ").append(i++).append(" ")
                    .append(c.at() == null ? "" : TS.format(c.at())).append("】\n");
            sb.append("员工：").append(c.userText()).append('\n');
            sb.append("助手：").append(c.assistantText()).append("\n\n");
        }
        return new Prompt(INDIVIDUAL_SYSTEM, sb.toString());
    }

    /** Pass②：多员工摘要 → 项目进度归类提示词。 */
    public Prompt project(List<WorkSummary> summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("本期共有 ").append(summaries.size()).append(" 位员工的工作摘要：\n\n");
        for (WorkSummary s : summaries) {
            sb.append("— 员工 ").append(s.getEmployeeId()).append("：\n");
            sb.append(s.getSummaryText() == null ? "(无摘要)" : s.getSummaryText()).append("\n\n");
        }
        return new Prompt(PROJECT_SYSTEM, sb.toString());
    }

    /** system + user 两段提示词。 */
    public record Prompt(String system, String user) {
    }
}
