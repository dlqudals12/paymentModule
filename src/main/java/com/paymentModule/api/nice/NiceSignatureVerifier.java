package com.paymentModule.api.nice;

import com.paymentModule.global.properties.payments.NiceApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 나이스페이 결제창 인증 결과의 위변조 검증값을 확인한다.
 */
@Component
@RequiredArgsConstructor
public class NiceSignatureVerifier {
    private final NiceApiProperties properties;

    /**
     * 공식 생성 규칙인 SHA-256(authToken + clientId + amount + secretKey)을 비교한다.
     */
    public boolean valid(String authToken, String clientId, String amount, String signature) {
        if (authToken == null || clientId == null || amount == null || signature == null
                || !clientId.equals(properties.getClientKey())) {
            return false;
        }

        byte[] expected = sha256(authToken + clientId + amount + properties.getSecretKey());
        try {
            return MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 콜백과 승인 요청이 같은 해시 계산을 사용하도록 한곳에서 생성한다.
     */
    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
