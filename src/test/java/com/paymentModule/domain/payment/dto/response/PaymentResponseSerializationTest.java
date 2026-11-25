package com.paymentModule.domain.payment.dto.response;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 큰 결제 ID가 브라우저로 전달될 때 숫자 정밀도를 잃지 않는지 확인한다.
 */
class PaymentResponseSerializationTest {

    /**
     * 결제 PK만 문자열로 보내고 결제 금액은 숫자로 유지한다.
     */
    @Test
    void largePaymentIdIsSerializedAsString() {
        Long paymentId = 913336296613762701L;
        PaymentResponse response = PaymentResponse.builder()
                .id(paymentId)
                .amount(10_000L)
                .build();

        // 실제 HTTP 응답에서 사용하는 Jackson 3으로 JSON 경계를 검증한다.
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));

        assertTrue(json.get("id").isString());
        assertEquals(paymentId.toString(), json.get("id").stringValue());
        assertTrue(json.get("amount").isNumber());
        assertEquals(10_000L, json.get("amount").longValue());
    }
}
