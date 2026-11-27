package com.paymentModule.domain.payment.processor;

import com.paymentModule.api.toss.TossPaymentApi;
import com.paymentModule.api.toss.dto.request.TossConfirmRequest;
import com.paymentModule.api.toss.dto.response.TossApiResponse;
import com.paymentModule.api.toss.dto.response.TossApiResult;
import com.paymentModule.api.toss.enums.TossPaymentMethod;
import com.paymentModule.api.toss.enums.TossPaymentStatus;
import com.paymentModule.data.enums.PaymentMethodType;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.global.exception.PaymentException;
import com.paymentModule.global.properties.payments.TossApiProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Toss API 응답을 공통 결제 결과로 변환하는 규칙을 검증한다.
 */
class TossPaymentProcessorTest {

    /**
     * 클라이언트 금액 대신 저장된 금액으로 승인하고 DONE을 SUCCESS로 변환한다.
     */
    @Test
    void confirmedPaymentUsesSavedAmount() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossPaymentProcessor processor = processor(tossPaymentApi);
        PaymentProcessorData payment = paymentData("order_unit_001", 1500L);
        PaymentRequest request = request("order_unit_001", "payment_unit_001", 1000L);
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.success(200,
                        response("order_unit_001", "payment_unit_001", 1500L, TossPaymentStatus.DONE)));

        PaymentProcessResult result = processor.payment(payment, request);

        ArgumentCaptor<TossConfirmRequest> captured = ArgumentCaptor.forClass(TossConfirmRequest.class);
        verify(tossPaymentApi).confirm(captured.capture(), eq("idempotency-unit-001"));
        assertEquals(1500L, captured.getValue().getAmount());
        assertEquals(PaymentStatus.SUCCESS, result.getStatus());
        assertEquals(1, result.getApiCalls().size());
        assertEquals(HttpMethod.POST, result.getApiCalls().getFirst().getHttpMethod());
        assertEquals("https://api.tosspayments.com/v1/payments/confirm",
                result.getApiCalls().getFirst().getRequestUrl());
        assertEquals("{\"orderId\":\"order_unit_001\",\"amount\":1500}",
                result.getApiCalls().getFirst().getRequestBody());
        assertTrue(result.getApiCalls().getFirst().getResponseBody().contains("\"status\":\"DONE\""));
        assertFalse(result.getApiCalls().getFirst().getResponseBody().contains("paymentKey"));
        assertEquals(PaymentMethodType.CARD, result.getPaymentMethod());
        assertEquals("payment_unit_001", result.getExternalPaymentId());
        assertEquals("transaction_payment_unit_001", result.getTransactionId());
        assertEquals(200, result.getHttpStatus());
    }

    /**
     * 승인 응답의 가맹점과 카드 상세 정보를 공통 결과로 전달한다.
     */
    @Test
    void confirmedPaymentIncludesCardDetails() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossApiResponse approved = response("order_card_001", "payment_card_001", 1500L,
                TossPaymentStatus.DONE);
        approved.setRequestedAt("2026-11-27T09:30:00+09:00");

        TossApiResponse.Card card = new TossApiResponse.Card();
        card.setIssuerCode("11");
        card.setNumber("123456******7890");
        card.setInstallmentPlanMonths(3);
        card.setApproveNo("12345678");
        approved.setCard(card);

        TossApiResponse.UrlInfo receipt = new TossApiResponse.UrlInfo();
        receipt.setUrl("https://example.com/receipt");
        approved.setReceipt(receipt);
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.success(200, approved));

        PaymentProcessResult result = processor(tossPaymentApi).payment(
                paymentData("order_card_001", 1500L),
                request("order_card_001", "payment_card_001", 1500L));

        assertEquals("DONE", result.getProviderStatus());
        assertEquals("https://example.com/receipt", result.getReceiptUrl());
        assertEquals("11", result.getCard().getIssuerCode());
        assertEquals("123456******7890", result.getCard().getMaskedNumber());
        assertEquals(3, result.getCard().getInstallmentMonths());
        assertNull(result.getVirtualAccount());
    }

    /**
     * 입금 대기 응답의 가상계좌를 결제 성공으로 바꾸지 않고 전달한다.
     */
    @Test
    void waitingPaymentIncludesVirtualAccount() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossApiResponse approved = response("order_virtual_001", "payment_virtual_001", 1500L,
                TossPaymentStatus.WAITING_FOR_DEPOSIT);
        approved.setMethod(TossPaymentMethod.VIRTUAL_ACCOUNT);

        TossApiResponse.VirtualAccount account = new TossApiResponse.VirtualAccount();
        account.setBankCode("20");
        account.setAccountNumber("123456789012");
        account.setDueDate("2026-11-28T23:59:59+09:00");
        approved.setVirtualAccount(account);
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.success(200, approved));

        PaymentProcessResult result = processor(tossPaymentApi).payment(
                paymentData("order_virtual_001", 1500L),
                request("order_virtual_001", "payment_virtual_001", 1500L));

        assertEquals(PaymentStatus.WAITING_FOR_PAYMENT, result.getStatus());
        assertEquals("20", result.getVirtualAccount().getBankCode());
        assertEquals("123456789012", result.getVirtualAccount().getAccountNumber());
        assertEquals("2026-11-28T23:59:59+09:00", result.getVirtualAccount().getDueDate());
        assertNull(result.getCard());
    }

    /**
     * 간편결제에서도 PG 가맹점 ID와 간편결제 제공사를 각각 유지한다.
     */
    @Test
    void easyPayKeepsProvider() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossApiResponse approved = response("order_easy_001", "payment_easy_001", 1500L,
                TossPaymentStatus.DONE);
        approved.setMethod(TossPaymentMethod.EASY_PAY);

        TossApiResponse.EasyPay easyPay = new TossApiResponse.EasyPay();
        easyPay.setProvider("TOSSPAY");
        easyPay.setAmount(1500L);
        approved.setEasyPay(easyPay);
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.success(200, approved));

        PaymentProcessResult result = processor(tossPaymentApi).payment(
                paymentData("order_easy_001", 1500L),
                request("order_easy_001", "payment_easy_001", 1500L));

        assertEquals(PaymentMethodType.EASY_PAY, result.getPaymentMethod());
        assertEquals("TOSSPAY", result.getEasyPay().getProvider());
    }

    /**
     * 토스의 결제 취소 상태를 승인 실패와 구별한다.
     */
    @Test
    void canceledPaymentIsNotFailed() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossPaymentProcessor processor = processor(tossPaymentApi);
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.success(200,
                        response("order_cancel_001", "payment_cancel_001", 1500L,
                                TossPaymentStatus.CANCELED)));

        PaymentProcessResult result = processor.payment(paymentData("order_cancel_001", 1500L),
                request("order_cancel_001", "payment_cancel_001", 1500L));

        assertEquals(PaymentStatus.CANCELLED, result.getStatus());
    }

    /**
     * 토스의 승인 실패 상태는 FAILED로 변환한다.
     */
    @Test
    void abortedPaymentIsFailed() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossPaymentProcessor processor = processor(tossPaymentApi);
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.success(200,
                        response("order_abort_001", "payment_abort_001", 1500L,
                                TossPaymentStatus.ABORTED)));

        PaymentProcessResult result = processor.payment(paymentData("order_abort_001", 1500L),
                request("order_abort_001", "payment_abort_001", 1500L));

        assertEquals(PaymentStatus.FAILED, result.getStatus());
    }

    /**
     * 통신 응답이 없으면 PG의 HTTP 응답 코드를 기록하지 않고 UNKNOWN으로 둔다.
     */
    @Test
    void communicationFailureHasNoHttpStatus() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossPaymentProcessor processor = processor(tossPaymentApi);
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.failure(502, "TOSS_COMMUNICATION_ERROR", "통신 실패"));

        PaymentException exception = assertThrows(PaymentException.class,
                () -> processor.payment(paymentData("order_error_001", 1500L),
                        request("order_error_001", "payment_error_001", 1500L)));
        PaymentProcessResult result = exception.getResult();

        assertEquals(PaymentStatus.UNKNOWN, result.getStatus());
        assertEquals(HttpMethod.POST, result.getApiCalls().getFirst().getHttpMethod());
        assertEquals("https://api.tosspayments.com/v1/payments/confirm",
                result.getApiCalls().getFirst().getRequestUrl());
        assertTrue(result.getApiCalls().getFirst().getResponseBody()
                .contains("\"code\":\"TOSS_COMMUNICATION_ERROR\""));
        assertNull(result.getHttpStatus());
        assertEquals("TOSS_COMMUNICATION_ERROR", result.getCode());
    }

    /**
     * 토스가 반환한 금액이 준비 금액과 다르면 승인 성공으로 처리하지 않는다.
     */
    @Test
    void mismatchedAmountRemainsUnknown() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossPaymentProcessor processor = processor(tossPaymentApi);
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.success(200,
                        response("order_mismatch_001", "payment_mismatch_001", 1400L,
                                TossPaymentStatus.DONE)));

        PaymentProcessResult result = processor.payment(paymentData("order_mismatch_001", 1500L),
                request("order_mismatch_001", "payment_mismatch_001", 1500L));

        assertEquals(PaymentStatus.UNKNOWN, result.getStatus());
        assertEquals("TOSS_RESULT_MISMATCH", result.getCode());
    }

    /**
     * 처리 중 재진입 시 토스 조회의 승인 완료 결과를 공통 성공 상태로 변환한다.
     */
    @Test
    void lookupCompletedPayment() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossPaymentProcessor processor = processor(tossPaymentApi);
        when(tossPaymentApi.paymentByKey("payment_lookup_001"))
                .thenReturn(TossApiResult.success(200,
                        response("order_lookup_001", "payment_lookup_001", 1500L,
                                TossPaymentStatus.DONE)));

        PaymentProcessResult result = processor.lookup(paymentData("order_lookup_001", 1500L),
                "payment_lookup_001");

        assertEquals(PaymentStatus.SUCCESS, result.getStatus());
        assertEquals(HttpMethod.GET, result.getApiCalls().getFirst().getHttpMethod());
        assertEquals("https://api.tosspayments.com/v1/payments/payment_lookup_001",
                result.getApiCalls().getFirst().getRequestUrl());
        assertNull(result.getApiCalls().getFirst().getRequestBody());
        assertTrue(result.getApiCalls().getFirst().getResponseBody().contains("\"status\":\"DONE\""));
        assertEquals("payment_lookup_001", result.getExternalPaymentId());
        assertEquals("transaction_payment_lookup_001", result.getTransactionId());
        assertEquals(200, result.getHttpStatus());
    }

    /**
     * 조회 결과가 준비 금액과 다르면 결제 성공으로 확정하지 않는다.
     */
    @Test
    void lookupMismatchedAmountRemainsUnknown() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossPaymentProcessor processor = processor(tossPaymentApi);
        when(tossPaymentApi.paymentByKey("payment_lookup_mismatch_001"))
                .thenReturn(TossApiResult.success(200,
                        response("order_lookup_mismatch_001", "payment_lookup_mismatch_001", 1400L,
                                TossPaymentStatus.DONE)));

        PaymentProcessResult result = processor.lookup(paymentData("order_lookup_mismatch_001", 1500L),
                "payment_lookup_mismatch_001");

        assertEquals(PaymentStatus.UNKNOWN, result.getStatus());
        assertEquals("TOSS_RESULT_MISMATCH", result.getCode());
    }

    /**
     * 토스가 결제 정보 부재를 명시하면 실패 시도로 분류한다.
     */
    @Test
    void lookupNotFoundBecomesFailed() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossPaymentProcessor processor = processor(tossPaymentApi);
        when(tossPaymentApi.paymentByKey("payment_not_found_001"))
                .thenReturn(TossApiResult.failure(404, "NOT_FOUND_PAYMENT", "결제를 찾을 수 없습니다."));

        PaymentException exception = assertThrows(PaymentException.class,
                () -> processor.lookup(paymentData("order_not_found_001", 1500L),
                        "payment_not_found_001"));
        PaymentProcessResult result = exception.getResult();

        assertEquals(PaymentStatus.FAILED, result.getStatus());
        assertEquals(HttpMethod.GET, result.getApiCalls().getFirst().getHttpMethod());
        assertEquals("https://api.tosspayments.com/v1/payments/payment_not_found_001",
                result.getApiCalls().getFirst().getRequestUrl());
        assertEquals(404, result.getHttpStatus());
    }

    /**
     * 조회 통신 오류는 결제 부재로 간주하지 않는다.
     */
    @Test
    void lookupCommunicationErrorRemainsUnknown() {
        TossPaymentApi tossPaymentApi = mock(TossPaymentApi.class);
        TossPaymentProcessor processor = processor(tossPaymentApi);
        when(tossPaymentApi.paymentByKey("payment_lookup_error_001"))
                .thenReturn(TossApiResult.failure(502, "TOSS_COMMUNICATION_ERROR", "통신 실패"));

        PaymentException exception = assertThrows(PaymentException.class,
                () -> processor.lookup(paymentData("order_lookup_error_001", 1500L),
                        "payment_lookup_error_001"));
        PaymentProcessResult result = exception.getResult();

        assertEquals(PaymentStatus.UNKNOWN, result.getStatus());
        assertNull(result.getHttpStatus());
    }

    /**
     * 외부 호출을 모킹한 토스 처리기를 만든다.
     */
    private TossPaymentProcessor processor(TossPaymentApi tossPaymentApi) {
        TossApiProperties properties = new TossApiProperties();
        properties.setBaseUrl("https://api.tosspayments.com");
        properties.setPath(new TossApiProperties.Path(
                "/v1/payments/confirm", "/v1/payments/{paymentKey}", "", "", ""));
        return new TossPaymentProcessor(tossPaymentApi, properties);
    }

    /**
     * 승인 요청에 사용할 준비된 결제를 만든다.
     */
    private PaymentProcessorData paymentData(String orderId, Long amount) {
        return PaymentProcessorData.builder()
                .orderId(orderId)
                .amount(amount)
                .idempotencyKey("idempotency-unit-001")
                .build();
    }

    /**
     * SDK에서 받은 승인값을 만든다.
     */
    private PaymentRequest request(String orderId, String paymentKey, Long amount) {
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "orderId", orderId);
        ReflectionTestUtils.setField(request, "externalPaymentId", paymentKey);
        ReflectionTestUtils.setField(request, "amount", amount);
        return request;
    }

    /**
     * 토스 승인 응답을 만든다.
     */
    private TossApiResponse response(String orderId, String paymentKey, Long amount,
                                     TossPaymentStatus status) {
        TossApiResponse response = new TossApiResponse();
        response.setOrderId(orderId);
        response.setPaymentKey(paymentKey);
        response.setLastTransactionKey("transaction_" + paymentKey);
        response.setTotalAmount(amount);
        response.setStatus(status);
        response.setMethod(TossPaymentMethod.CARD);
        response.setApprovedAt("2026-11-12T10:00:00+09:00");
        return response;
    }
}
