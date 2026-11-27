package com.paymentModule.domain.payment.processor;

import com.paymentModule.api.nice.NicePaymentApi;
import com.paymentModule.api.nice.NiceSignatureVerifier;
import com.paymentModule.api.nice.dto.response.NiceApiResponse;
import com.paymentModule.api.nice.dto.response.NiceApiResult;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.global.properties.payments.NiceApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * NICEPAY 응답을 공통 결제 상태로 바꾸고 인증 서명을 검증한다.
 */
class NicePaymentProcessorTest {

    /**
     * 정상 서명과 승인 JSON의 결제 식별자·카드 정보를 저장 가능한 결과로 바꾼다.
     */
    @Test
    void approvedCardPayment() throws Exception {
        NiceApiProperties properties = properties();
        StubNicePaymentApi api = new StubNicePaymentApi(properties);
        NiceApiResponse response = response("paid", "CARD", 1000L);
        NiceApiResponse.Card card = new NiceApiResponse.Card();
        card.setCardCode("04");
        card.setCardNum("1234****5678");
        response.setCard(card);
        api.approval = NiceApiResult.success(200, response);

        NicePaymentProcessor processor = new NicePaymentProcessor(api, properties,
                new NiceSignatureVerifier(properties));
        PaymentRequest request = request(properties, 1000L);
        processor.paymentValidation(request);
        PaymentProcessResult result = processor.payment(paymentData(), request);

        assertEquals(PaymentStatus.SUCCESS, result.getStatus());
        assertEquals("tid-001", result.getExternalPaymentId());
        assertEquals("1234****5678", result.getCard().getMaskedNumber());
        assertEquals(HttpMethod.POST, result.getApiCalls().getFirst().getHttpMethod());
    }

    /**
     * 가상계좌 발급 후 READY는 입금 대기 상태이며 조회는 GET으로 기록한다.
     */
    @Test
    void virtualAccountWaitsForPayment() {
        NiceApiProperties properties = properties();
        StubNicePaymentApi api = new StubNicePaymentApi(properties);
        NiceApiResponse response = response("ready", "VBANK", 1000L);
        NiceApiResponse.VirtualAccount vbank = new NiceApiResponse.VirtualAccount();
        vbank.setVbankNumber("1234567890");
        response.setVbank(vbank);
        api.lookup = NiceApiResult.success(200, response);

        NicePaymentProcessor processor = new NicePaymentProcessor(api, properties,
                new NiceSignatureVerifier(properties));
        PaymentProcessResult result = processor.lookup(paymentData(), "tid-001");

        assertEquals(PaymentStatus.WAITING_FOR_PAYMENT, result.getStatus());
        assertEquals("1234567890", result.getVirtualAccount().getAccountNumber());
        assertEquals(HttpMethod.GET, result.getApiCalls().getFirst().getHttpMethod());
    }

    /**
     * 승인 응답 금액이 준비 금액과 다르면 성공으로 확정하지 않는다.
     */
    @Test
    void mismatchedApprovalIsUnknown() throws Exception {
        NiceApiProperties properties = properties();
        StubNicePaymentApi api = new StubNicePaymentApi(properties);
        api.approval = NiceApiResult.success(200, response("paid", "CARD", 2000L));

        NicePaymentProcessor processor = new NicePaymentProcessor(api, properties,
                new NiceSignatureVerifier(properties));
        PaymentProcessResult result = processor.payment(paymentData(), request(properties, 1000L));

        assertEquals(PaymentStatus.UNKNOWN, result.getStatus());
    }

    /**
     * 인증 결과의 서명이 바뀌면 PG 승인 호출 전 요청을 거절한다.
     */
    @Test
    void changedSignatureIsRejected() {
        NiceApiProperties properties = properties();
        NicePaymentProcessor processor = new NicePaymentProcessor(
                new StubNicePaymentApi(properties), properties, new NiceSignatureVerifier(properties));
        PaymentRequest request = PaymentRequest.builder()
                .provider(PaymentProvider.NICE)
                .orderId("order-001")
                .externalPaymentId("tid-001")
                .amount(1000L)
                .clientId(properties.getClientKey())
                .authToken("token-001")
                .signature("incorrect")
                .build();

        assertThrows(ResponseStatusException.class, () -> processor.paymentValidation(request));
    }

    /**
     * 각 테스트에서 운영 키가 아닌 고정된 로컬 입력값을 구성한다.
     */
    private NiceApiProperties properties() {
        NiceApiProperties properties = new NiceApiProperties();
        properties.setClientKey("test-client");
        properties.setSecretKey("test-secret");
        properties.setBaseUrl("https://sandbox-api.nicepay.co.kr");
        properties.setPath(new NiceApiProperties.Path("/v1/payments/{tid}"));
        return properties;
    }

    /**
     * 저장된 주문번호와 서버 금액을 흉내 낸다.
     */
    private PaymentProcessorData paymentData() {
        return PaymentProcessorData.builder().orderId("order-001").amount(1000L).build();
    }

    /**
     * NICEPAY 공식 서명 규칙으로 유효한 인증 요청을 만든다.
     */
    private PaymentRequest request(NiceApiProperties properties, Long amount) throws Exception {
        String authToken = "token-001";
        String source = authToken + properties.getClientKey() + amount + properties.getSecretKey();
        String signature = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        return PaymentRequest.builder()
                .provider(PaymentProvider.NICE)
                .orderId("order-001")
                .externalPaymentId("tid-001")
                .amount(amount)
                .clientId(properties.getClientKey())
                .authToken(authToken)
                .signature(signature)
                .build();
    }

    /**
     * 승인과 조회에 공통인 결제 식별 응답을 만든다.
     */
    private NiceApiResponse response(String status, String method, Long amount) {
        NiceApiResponse response = new NiceApiResponse();
        response.setResultCode("0000");
        response.setTid("tid-001");
        response.setOrderId("order-001");
        response.setAmount(amount);
        response.setStatus(status);
        response.setPayMethod(method);
        return response;
    }

    /**
     * 실제 HTTP 호출 없이 PG API 결과만 바꾸는 테스트용 구현이다.
     */
    private static class StubNicePaymentApi extends NicePaymentApi {
        private NiceApiResult approval;
        private NiceApiResult lookup;

        private StubNicePaymentApi(NiceApiProperties properties) {
            super(properties);
        }

        @Override
        public NiceApiResult confirm(String tid, Long amount) {
            return approval;
        }

        @Override
        public NiceApiResult payment(String tid) {
            return lookup;
        }
    }
}
