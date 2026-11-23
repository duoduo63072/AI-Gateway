package com.company.aigateway.token;

import com.fasterxml.jackson.databind.JsonNode;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

/**
 * Token 用量兜底估算（仅在上游未回传 usage 时使用）。
 * 注意：用 cl100k_base BPE 估算，Qwen 使用自有 tokenizer，与百炼计费口径会有偏差，
 * 因此结果仅作 *_tokens_est，并以 token_source=ESTIMATED 标识。
 */
@Component
public class TokenEstimator {

    private final Encoding encoding;

    public TokenEstimator() {
        this.encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    }

    public int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /** 估算整个 messages 数组的 token 数（含少量 per-message 结构开销）。 */
    public int estimateMessages(JsonNode messages) {
        if (messages == null || !messages.isArray()) {
            return 0;
        }
        int total = 0;
        for (JsonNode message : messages) {
            total += 4; // 每条消息的角色/分隔结构开销近似
            JsonNode role = message.get("role");
            if (role != null && role.isTextual()) {
                total += estimateText(role.asText());
            }
            total += countContent(message.get("content"));
        }
        return total;
    }

    private int countContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return 0;
        }
        if (content.isTextual()) {
            return estimateText(content.asText());
        }
        // 多模态 content blocks：仅累计文本部分
        if (content.isArray()) {
            int sum = 0;
            for (JsonNode part : content) {
                JsonNode text = part.get("text");
                if (text != null && text.isTextual()) {
                    sum += estimateText(text.asText());
                }
            }
            return sum;
        }
        return 0;
    }
}
