package com.company.aigateway.security;

import com.company.aigateway.config.GatewayProperties;
import com.company.aigateway.persistence.repository.ApiClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 解析网关侧 API Key：先匹配可选引导主密钥，否则按 SHA-256 查 DB 中已启用的客户端。
 * 明文 Key 不落库、不打日志。
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiClientRepository apiClientRepository;
    private final GatewayProperties properties;

    public Mono<AuthenticatedClient> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Mono.empty();
        }
        String master = properties.getAuth().getMasterKey();
        if (master != null && !master.isBlank() && constantTimeEquals(master, presentedKey)) {
            return Mono.just(new AuthenticatedClient(null, "master", "master", "系统管理员", "ADMIN"));
        }
        String hash = sha256Hex(presentedKey);
        return apiClientRepository.findByApiKeyHashAndEnabledIsTrue(hash)
                .map(c -> new AuthenticatedClient(c.getId(), c.getClientKeyId(), c.getEmployeeId(),
                        c.getDisplayName(), c.getRole() == null ? "EMPLOYEE" : c.getRole()));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
