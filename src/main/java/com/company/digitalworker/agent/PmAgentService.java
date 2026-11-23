package com.company.digitalworker.agent;

import com.company.aigateway.security.AuthenticatedClient;
import com.company.digitalworker.config.DigitalWorkerProperties;
import com.company.digitalworker.summary.WorkSummaryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * pm-agent 的对话发动机：给定问题 + 提问者身份，跑"调上游(带 tools) → 模型要调工具就本地取数回灌 →
 * 直到产出最终回答"的有界循环。工具取数全程按 {@link AgentScope} 强制权限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmAgentService {

    private final AgentLlmClient llmClient;
    private final PmDataService pmDataService;
    private final RosterService rosterService;
    private final WorkSummaryService workSummaryService;
    private final DigitalWorkerProperties properties;
    private final ObjectMapper objectMapper;

    /** 回答一个问题。失败/超时降级为友好提示，不抛给客户端。 */
    public Mono<String> answer(String question, AuthenticatedClient client) {
        AgentScope scope = AgentScope.from(client);
        return rosterService.roster().flatMap(roster -> {
            String system = systemPrompt(scope, roster);
            ArrayNode tools = toolDefs();
            ArrayNode messages = objectMapper.createArrayNode();
            messages.add(userText(question));
            return runLoop(system, tools, messages, scope, properties.getAgent().getMaxToolIterations());
        })
                .map(t -> (t == null || t.isBlank()) ? "抱歉，我没能整理出结果。" : t)
                .onErrorResume(e -> {
                    log.warn("pm-agent answer failed: {}", e.toString());
                    return Mono.just("抱歉，处理你的问题时出错了（" + e.getClass().getSimpleName()
                            + "）。可稍后再试或把问题问得更具体些。");
                });
    }

    // ---------------------------------------------------------------- 工具循环

    private Mono<String> runLoop(String system, ArrayNode tools, ArrayNode messages,
                                 AgentScope scope, int remaining) {
        ObjectNode body = buildBody(system, tools, messages);
        return llmClient.call(body).flatMap(resp -> {
            JsonNode content = resp.path("content");
            String stop = resp.path("stop_reason").asText("");
            if ("tool_use".equals(stop) && remaining > 0) {
                messages.add(assistantTurn(content));
                return executeTools(content, scope).flatMap(results -> {
                    messages.add(userBlocks(results));
                    return runLoop(system, tools, messages, scope, remaining - 1);
                });
            }
            return Mono.just(extractText(content));
        });
    }

    private Mono<ArrayNode> executeTools(JsonNode content, AgentScope scope) {
        List<JsonNode> uses = new ArrayList<>();
        if (content.isArray()) {
            for (JsonNode b : content) {
                if ("tool_use".equals(b.path("type").asText(""))) {
                    uses.add(b);
                }
            }
        }
        return Flux.fromIterable(uses)
                .concatMap(tu -> executeOne(tu, scope)
                        .map(result -> toolResultBlock(tu.path("id").asText(""), result)))
                .collectList()
                .map(list -> {
                    ArrayNode arr = objectMapper.createArrayNode();
                    list.forEach(arr::add);
                    return arr;
                });
    }

    private Mono<String> executeOne(JsonNode toolUse, AgentScope scope) {
        String name = toolUse.path("name").asText("");
        JsonNode input = toolUse.path("input");
        OffsetDateTime[] win = window(input);
        log.info("pm-agent tool call: {} scope={} input={}", name,
                scope.all() ? "ALL" : "SELF(" + scope.employeeId() + ")", input);
        return switch (name) {
            case "query_work_summaries" -> pmDataService
                    .queryWorkSummaries(scope, textOrNull(input, "employee_id"), win[0], win[1])
                    .map(this::compact);
            case "query_project_progress" -> pmDataService
                    .queryProjectProgress(scope, win[0], win[1], textOrNull(input, "project_key"))
                    .map(this::compact);
            case "refresh_data" -> workSummaryService
                    .runDailyGuarded(LocalDate.now(), properties.getAgent().getRefreshDebounce())
                    .map(this::refreshText);
            case "query_ai_usage_cost" -> aiUsageCost(input, scope);
            default -> Mono.just("{\"error\":\"unknown tool: " + name + "\"}");
        };
    }

    /** AI 使用量与成本工具：日期窗默认"本月"，权限交由 PmDataService 按 scope 强制。 */
    private Mono<String> aiUsageCost(JsonNode input, AgentScope scope) {
        LocalDate[] w = monthWindow(input);
        return pmDataService.queryAiUsageCost(scope, textOrNull(input, "employee_id"), w[0], w[1])
                .map(this::compact);
    }

    /** 解析使用量查询的日期窗：默认 [本月1号, 今天+1)；上界 exclusive 以包含 to 当天。 */
    private LocalDate[] monthWindow(JsonNode input) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate to = parseDate(textOrNull(input, "to"), today);
        LocalDate from = parseDate(textOrNull(input, "from"), today.withDayOfMonth(1));
        return new LocalDate[]{from, to.plusDays(1)};
    }

    private String refreshText(WorkSummaryService.RefreshResult r) {
        return switch (r.status()) {
            case "REFRESHED" -> "已重新分析今日最新数据：覆盖 " + r.result().employees()
                    + " 名员工、" + r.result().projects() + " 个项目。可基于最新数据回答了。";
            case "SKIPPED_RECENT" -> "数据在 " + r.minutesAgo()
                    + " 分钟前刚更新过，已是最新，无需重复刷新。";
            case "IN_PROGRESS" -> "数据正在后台更新中，请稍候几十秒再问。";
            default -> "刷新状态未知。";
        };
    }

    // ---------------------------------------------------------------- 提示词 / 工具定义

    private String systemPrompt(AgentScope scope, String roster) {
        String today = LocalDate.now().toString();
        String name = (scope.displayName() == null || scope.displayName().isBlank())
                ? "（姓名未知）" : scope.displayName();
        String who = scope.all()
                ? "提问者是 " + name + "（工号 " + scope.employeeId() + "，角色 " + scope.role()
                        + "），可查看全部员工与项目；也可以查询你本人的工作摘要（用你自己的工号 " + scope.employeeId() + "）。"
                : "提问者是 " + name + "（工号 " + scope.employeeId()
                        + "），只能查看自己的工作与自己参与的项目。";
        String rosterBlock = (roster == null || roster.isBlank()) ? ""
                : "\n公司花名册（姓名 工号）：" + roster
                        + "\n注意：花名册仅用于把用户提到的姓名换算成工号、或把工号显示成姓名；它不代表你有权查看名册中其他人的数据。"
                        + "可见范围仍由系统按提问者身份强制过滤，工具查不到就如实说\"没有相关记录 / 无权查看\"。";
        return """
                你是公司的"数字项目经理"助手，基于公司内部沉淀的工作数据，回答关于【项目进展】【员工工作情况】和【AI 使用量与成本】的问题。
                今天是 %s。%s%s
                工作方式：
                1. 不要凭空编造。回答前先用工具检索真实数据：query_work_summaries（员工工作摘要）、query_project_progress（项目进度）、query_ai_usage_cost（员工的 AI 使用量与成本）。
                2. 工具返回的数据已按提问者权限过滤；你只能依据返回的数据作答，查不到的就如实说"没有相关记录 / 无权查看"。
                3. 用中文，简洁有条理；提及任何员工时统一用「姓名（工号）」格式（如"张鹏（11230）"）；涉及多人多项目时分点列出，可引用项目名、日期。
                4. 若检索为空，直接说明该时间段无数据，并提示可能原因（如该员工尚未通过网关产生对话、或日期范围不对）。
                5. 若用户表示"数据不是最新 / 请更新 / 刚做的工作没显示"，先调用 refresh_data 重新分析最新数据，再用 query_* 重新查询后回答；
                   refresh 需要几十秒，若返回"已是最新"或"正在更新中"，把这一情况如实转告用户。
                6. 涉及"使用量 / 成本 / 花费 / 额度"的问题用 query_ai_usage_cost：成本单位为人民币元(costCny)、默认统计本月；
                   汇报时按成本从高到低列出，可对比额度(quotaCny)并标出接近或超额者。若各人成本普遍为 0 或明显偏低，
                   很可能是价目表(model_pricing)尚未配齐单价，应如实提示"成本可能未计全，请管理员补充模型单价"，不要当作真的没花费。
                """.formatted(today, who, rosterBlock);
    }

    private ArrayNode toolDefs() {
        ArrayNode tools = objectMapper.createArrayNode();

        ObjectNode wsProps = objectMapper.createObjectNode();
        wsProps.set("employee_id", strProp("工号，可选。领导查特定员工时用；员工本人无需填（会强制为本人）。"));
        wsProps.set("from", strProp("起始日期 YYYY-MM-DD，可选。"));
        wsProps.set("to", strProp("结束日期 YYYY-MM-DD（含当天），可选。"));
        tools.add(tool("query_work_summaries",
                "查询员工每日工作摘要（某人某段时间在做什么、关键任务、状态）。", wsProps));

        ObjectNode ppProps = objectMapper.createObjectNode();
        ppProps.set("from", strProp("起始日期 YYYY-MM-DD，可选。"));
        ppProps.set("to", strProp("结束日期 YYYY-MM-DD（含当天），可选。"));
        ppProps.set("project_key", strProp("项目标识，可选。只看某个项目时用。"));
        tools.add(tool("query_project_progress",
                "查询项目进度（各项目本期进展、参与者、状态信号）。", ppProps));

        tools.add(tool("refresh_data",
                "重新分析最新的工作数据（把刚发生、尚未归纳的对话纳入摘要）。"
                        + "当用户说数据不是最新、要求更新、或刚做的工作查不到时调用。耗时几十秒。",
                objectMapper.createObjectNode()));

        ObjectNode usageProps = objectMapper.createObjectNode();
        usageProps.set("employee_id", strProp("工号，可选。领导查某个员工时用；员工本人无需填（会强制为本人）。"));
        usageProps.set("from", strProp("起始日期 YYYY-MM-DD，可选。默认本月 1 号。"));
        usageProps.set("to", strProp("结束日期 YYYY-MM-DD（含当天），可选。默认今天。"));
        tools.add(tool("query_ai_usage_cost",
                "查询员工的 AI 使用量与成本：每人的请求数(requests)、token 数(totalTokens)、"
                        + "按公司价目折算的人民币成本(costCny)、以及月度额度(quotaCny，无则不限额)。"
                        + "默认统计本月；领导可查全部员工，员工只能查自己。", usageProps));

        return tools;
    }

    private ObjectNode tool(String name, String description, ObjectNode properties) {
        ObjectNode t = objectMapper.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        t.set("input_schema", schema);
        return t;
    }

    private ObjectNode strProp(String description) {
        ObjectNode p = objectMapper.createObjectNode();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    // ---------------------------------------------------------------- 消息构造 / 工具结果

    private ObjectNode buildBody(String system, ArrayNode tools, ArrayNode messages) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getAgent().getModel());
        body.put("max_tokens", properties.getAgent().getMaxTokens());
        body.put("system", system);
        body.set("tools", tools);
        body.set("messages", messages);
        return body;
    }

    private JsonNode userText(String text) {
        ObjectNode m = objectMapper.createObjectNode();
        m.put("role", "user");
        m.put("content", text);
        return m;
    }

    private JsonNode assistantTurn(JsonNode content) {
        ObjectNode m = objectMapper.createObjectNode();
        m.put("role", "assistant");
        m.set("content", content == null ? objectMapper.createArrayNode() : content);
        return m;
    }

    private JsonNode userBlocks(ArrayNode blocks) {
        ObjectNode m = objectMapper.createObjectNode();
        m.put("role", "user");
        m.set("content", blocks);
        return m;
    }

    private ObjectNode toolResultBlock(String toolUseId, String result) {
        ObjectNode b = objectMapper.createObjectNode();
        b.put("type", "tool_result");
        b.put("tool_use_id", toolUseId);
        b.put("content", result);
        return b;
    }

    private String extractText(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode b : content) {
                if ("text".equals(b.path("type").asText(""))) {
                    sb.append(b.path("text").asText(""));
                }
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 流式工具循环（真流式）

    /**
     * 流式回答：与 {@link #answer} 同一套工具循环与权限，但每轮流式调上游——最终答案的 text
     * 增量边生成边 emit（打字机）。工具轮的 tool_use 累积后本地执行、回灌，再起下一轮。
     * 出错/超时降级为一段友好文本，保证下游 SSE 正常收尾、不挂死。
     */
    public Flux<String> answerStream(String question, AuthenticatedClient client) {
        AgentScope scope = AgentScope.from(client);
        return rosterService.roster().flatMapMany(roster -> {
            String system = systemPrompt(scope, roster);
            ArrayNode tools = toolDefs();
            ArrayNode messages = objectMapper.createArrayNode();
            messages.add(userText(question));
            return runLoopStream(system, tools, messages, scope, properties.getAgent().getMaxToolIterations());
        }).onErrorResume(e -> {
            log.warn("pm-agent answerStream failed: {}", e.toString());
            return Flux.just("抱歉，处理你的问题时出错了（" + e.getClass().getSimpleName()
                    + "）。可稍后再试或把问题问得更具体些。");
        });
    }

    private Flux<String> runLoopStream(String system, ArrayNode tools, ArrayNode messages,
                                       AgentScope scope, int remaining) {
        AgentStreamDecoder decoder = new AgentStreamDecoder(objectMapper);
        ObjectNode body = buildStreamBody(system, tools, messages);

        // 本轮：边解析上游 SSE 边把 text 增量透传给下游（concatMap 保证顺序、单线程 accept）
        Flux<String> thisRound = llmClient.callStream(body)
                .concatMap(event -> {
                    List<String> pieces = new ArrayList<>();
                    decoder.accept(event.data(), pieces::add);
                    return Flux.fromIterable(pieces);
                });

        // 本轮结束后（defer 保证此时 decoder 已读完整轮）：要调工具则执行+回灌+下一轮，否则收尾
        Flux<String> next = Flux.defer(() -> {
            if ("tool_use".equals(decoder.stopReason()) && decoder.hasToolUse() && remaining > 0) {
                messages.add(assistantTurn(decoder.assistantContent()));
                return executeToolCalls(decoder.toolCalls(), scope)
                        .flatMapMany(results -> {
                            messages.add(userBlocks(results));
                            return runLoopStream(system, tools, messages, scope, remaining - 1);
                        });
            }
            return Flux.<String>empty();
        });

        return Flux.concat(thisRound, next);
    }

    private Mono<ArrayNode> executeToolCalls(List<AgentStreamDecoder.ToolCall> calls, AgentScope scope) {
        return Flux.fromIterable(calls)
                .concatMap(tc -> {
                    ObjectNode tu = objectMapper.createObjectNode();
                    tu.put("name", tc.name());
                    tu.set("input", tc.input());
                    return executeOne(tu, scope).map(result -> toolResultBlock(tc.id(), result));
                })
                .collectList()
                .map(list -> {
                    ArrayNode arr = objectMapper.createArrayNode();
                    list.forEach(arr::add);
                    return arr;
                });
    }

    private ObjectNode buildStreamBody(String system, ArrayNode tools, ArrayNode messages) {
        ObjectNode body = buildBody(system, tools, messages);
        body.put("stream", true);
        return body;
    }

    // ---------------------------------------------------------------- 辅助

    private OffsetDateTime[] window(JsonNode input) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate to = parseDate(textOrNull(input, "to"), today);
        LocalDate from = parseDate(textOrNull(input, "from"),
                to.minusDays(properties.getAgent().getDefaultLookbackDays()));
        OffsetDateTime start = from.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime(); // 含 to 当天
        return new OffsetDateTime[]{start, end};
    }

    private LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String textOrNull(JsonNode input, String field) {
        JsonNode v = input.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText(null);
    }

    private String compact(JsonNode node) {
        String s = node.toString();
        return s.length() > 12000 ? s.substring(0, 12000) + "...(已截断)" : s;
    }
}
