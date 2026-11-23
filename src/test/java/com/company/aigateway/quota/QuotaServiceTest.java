package com.company.aigateway.quota;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QuotaService.isFirstTurn 的纯逻辑测试：决定「每会话只告警一次」是否准确。
 */
class QuotaServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode body(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void firstTurn_anthropicSingleUser() {
        // Anthropic：system 在顶层，messages 只有一条 user
        assertTrue(QuotaService.isFirstTurn(body("""
                {"system":"you are helpful","messages":[{"role":"user","content":"hi"}]}
                """)));
    }

    @Test
    void firstTurn_openAiSystemPlusUser() {
        // OpenAI：system 在 messages 内，但仍无 assistant → 第一轮
        assertTrue(QuotaService.isFirstTurn(body("""
                {"messages":[{"role":"system","content":"sys"},{"role":"user","content":"hi"}]}
                """)));
    }

    @Test
    void notFirstTurn_hasAssistant() {
        assertFalse(QuotaService.isFirstTurn(body("""
                {"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"hello"},{"role":"user","content":"again"}]}
                """)));
    }

    @Test
    void notFirstTurn_toolLoopHasAssistant() {
        // 工具调用循环：assistant(tool_use) + user(tool_result) → 不再重复告警
        assertFalse(QuotaService.isFirstTurn(body("""
                {"messages":[{"role":"user","content":"do x"},{"role":"assistant","content":[{"type":"tool_use"}]},{"role":"user","content":[{"type":"tool_result"}]}]}
                """)));
    }

    @Test
    void notFirstTurn_whenMessagesMissingOrMalformed() {
        // 形态不明 → 保守判 false，不注入
        assertFalse(QuotaService.isFirstTurn(body("{}")));
        assertFalse(QuotaService.isFirstTurn(body("{\"messages\":\"oops\"}")));
        assertFalse(QuotaService.isFirstTurn(null));
    }
}
