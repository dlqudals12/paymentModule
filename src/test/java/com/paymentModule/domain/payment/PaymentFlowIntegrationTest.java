package com.paymentModule.domain.payment;

import com.api.toss.TossPaymentApi;
import com.api.toss.dto.request.TossConfirmRequest;
import com.api.toss.dto.response.TossApiResponse;
import com.api.toss.dto.response.TossApiResult;
import com.api.toss.enums.TossPaymentMethod;
import com.api.toss.enums.TossPaymentStatus;
import com.paymentModule.PaymentModuleApplication;
import com.paymentModule.data.dto.user.CustomUserDetail;
import com.paymentModule.data.enums.PaymentHistoryType;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.enums.UserRoleType;
import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.data.model.entity.payment.PaymentApiLog;
import com.paymentModule.data.model.entity.user.User;
import com.paymentModule.data.repository.payment.PaymentApiLogRepository;
import com.paymentModule.data.repository.payment.PaymentHistoryRepository;
import com.paymentModule.data.repository.payment.PaymentRepository;
import com.paymentModule.domain.payment.controller.PaymentController;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.domain.payment.dto.response.PaymentResponse;
import com.paymentModule.domain.payment.event.PaymentCompletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 실제 DB 트랜잭션으로 결제 준비부터 PG 승인 결과 저장까지 검증한다.
 * 토스 외부 호출만 모킹하며 완료 이벤트의 발행 여부도 확인한다.
 */
@RecordApplicationEvents
@SpringBootTest(classes = PaymentModuleApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-flow;MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.sql.init.mode=never",
        "spring.docker.compose.enabled=false"
})
class PaymentFlowIntegrationTest {

    @Autowired
    private PaymentController paymentController;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    @Autowired
    private PaymentApiLogRepository paymentApiLogRepository;

    @Autowired
    private ApplicationEvents applicationEvents;

    @MockitoBean
    private TossPaymentApi tossPaymentApi;

    /**
     * 테스트마다 결제 데이터를 비우고 인증된 사용자를 설정한다.
     */
    @BeforeEach
    void prepare() {
        paymentApiLogRepository.deleteAllInBatch();
        paymentHistoryRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();

        User user = User.builder()
                .id(101L)
                .userId("payment-tester")
                .password("unused")
                .email("payment@example.com")
                .userName("결제 테스트")
                .role(UserRoleType.USER)
                .build();
        CustomUserDetail detail = new CustomUserDetail(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(detail, "", detail.getAuthorities()));
    }

    /**
     * 테스트가 끝나면 현재 스레드의 인증 정보를 제거한다.
     */
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 준비, 승인, 이력, 통신 로그, 완료 이벤트가 한 흐름으로 이어지는지 확인한다.
     */
    @Test
    void readyAndPaymentSuccess() {
        String orderId = "order_success_001";
        PaymentResponse ready = paymentController.ready(readyRequest(orderId, 1500L)).getData();

        assertEquals(PaymentStatus.READY, ready.getStatus());
        assertNotNull(ready.getPaymentId());
        assertEquals(1, paymentHistoryRepository.count());
        assertEquals(0, paymentApiLogRepository.count());

        TossApiResponse approved = tossResponse(orderId, "payment_success_001", 1500L,
                TossPaymentStatus.DONE);
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    //외부 PG 호출 중에는 DB 트랜잭션을 유지하지 않는다.
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    return TossApiResult.success(200, approved);
                });

        PaymentRequest request = paymentRequest(orderId, "payment_success_001", 1500L);
        PaymentResponse completed = paymentController.payment(request).getData();

        assertEquals(PaymentStatus.SUCCESS, completed.getStatus());
        Payment saved = paymentRepository.findById(ready.getPaymentId()).orElseThrow();
        assertEquals("payment_success_001", saved.getTransactionId());
        assertNotNull(saved.getApprovedAt());

        Set<PaymentHistoryType> historyTypes = paymentHistoryRepository.findAll().stream()
                .map(history -> history.getHistoryType())
                .collect(Collectors.toSet());
        assertEquals(Set.of(PaymentHistoryType.CREATED, PaymentHistoryType.PAYMENT_REQUESTED,
                PaymentHistoryType.PAYMENT_SUCCEEDED), historyTypes);
        assertEquals(3, paymentHistoryRepository.count());

        PaymentApiLog log = paymentApiLogRepository.findAll().getFirst();
        assertEquals(200, log.getResponseStatus());
        assertTrue(log.getSuccessYn());
        assertEquals("POST", log.getHttpMethod());
        assertEquals("https://api.tosspayments.com/v1/payments/confirm", log.getRequestUrl());
        assertNull(log.getRequestHeader());
        assertNull(log.getRequestBody());
        assertNull(log.getResponseBody());
        PaymentCompletedEvent event = applicationEvents.stream(PaymentCompletedEvent.class)
                .findFirst().orElseThrow();
        assertEquals(ready.getPaymentId(), event.paymentId());
        assertEquals(PaymentProvider.TOSS, event.provider());
        assertEquals(orderId, event.orderId());
        assertEquals(1500L, event.amount());

        //중복 호출은 저장된 성공 결과만 반환하고 PG 재승인과 이벤트 중복 발행을 막는다.
        assertEquals(PaymentStatus.SUCCESS, paymentController.payment(request).getData().getStatus());
        assertEquals(3, paymentHistoryRepository.count());
        assertEquals(1, paymentApiLogRepository.count());
        assertEquals(1, applicationEvents.stream(PaymentCompletedEvent.class).count());

        assertThrows(ResponseStatusException.class,
                () -> paymentController.ready(readyRequest(orderId, 1500L)));

        verify(tossPaymentApi).confirm(any(TossConfirmRequest.class), anyString());
    }

    /**
     * 새로고침으로 같은 주문을 다시 준비하면 결제 시도를 재사용하고 금액만 갱신한다.
     */
    @Test
    void repeatedReadyUpdatesAmount() {
        PaymentResponse first = paymentController.ready(
                readyRequest("order_refresh_001", 1500L)).getData();
        PaymentResponse repeated = paymentController.ready(
                readyRequest("order_refresh_001", 2000L)).getData();

        assertEquals(first.getPaymentId(), repeated.getPaymentId());
        assertEquals(PaymentStatus.READY, repeated.getStatus());
        assertEquals(2000L, repeated.getAmount());
        assertEquals(1, paymentRepository.count());
        assertEquals(1, paymentHistoryRepository.count());
        verifyNoInteractions(tossPaymentApi);
    }

    /**
     * 실패한 결제 행은 그대로 두고 같은 주문의 새 준비 시도를 생성한다.
     */
    @Test
    void failedPaymentAllowsNewReady() {
        String orderId = "order_retry_001";
        PaymentResponse first = paymentController.ready(readyRequest(orderId, 1500L)).getData();
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.failure(400, "INVALID_REQUEST", "승인 거절"));

        assertThrows(ResponseStatusException.class,
                () -> paymentController.payment(paymentRequest(orderId, "payment_retry_001", 1500L)));

        PaymentResponse repeated = paymentController.ready(readyRequest(orderId, 1500L)).getData();

        assertNotEquals(first.getPaymentId(), repeated.getPaymentId());
        assertEquals(PaymentStatus.FAILED,
                paymentRepository.findById(first.getPaymentId()).orElseThrow().getStatus());
        assertEquals(PaymentStatus.READY,
                paymentRepository.findById(repeated.getPaymentId()).orElseThrow().getStatus());
        assertEquals(2, paymentRepository.count());
    }

    /**
     * 클라이언트 금액이 준비 금액과 다르면 PG 호출 전에 중단하는지 확인한다.
     */
    @Test
    void differentAmountStopsBeforeApproval() {
        PaymentResponse ready = paymentController.ready(readyRequest("order_amount_001", 1500L)).getData();

        assertThrows(ResponseStatusException.class,
                () -> paymentController.payment(paymentRequest("order_amount_001", "payment_amount_001", 1000L)));

        assertEquals(PaymentStatus.READY,
                paymentRepository.findById(ready.getPaymentId()).orElseThrow().getStatus());
        assertEquals(1, paymentHistoryRepository.count());
        assertEquals(0, paymentApiLogRepository.count());
        assertEquals(0, applicationEvents.stream(PaymentCompletedEvent.class).count());
        verifyNoInteractions(tossPaymentApi);
    }

    /**
     * PG 응답이 없는 통신 오류는 실패로 단정하지 않고 UNKNOWN으로 기록한다.
     */
    @Test
    void communicationErrorRemainsUnknown() {
        PaymentResponse ready = paymentController.ready(readyRequest("order_unknown_001", 1500L)).getData();
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.failure(502, "TOSS_COMMUNICATION_ERROR", "통신 실패"));

        assertThrows(ResponseStatusException.class,
                () -> paymentController.payment(paymentRequest("order_unknown_001", "payment_unknown_001", 1500L)));

        assertEquals(PaymentStatus.UNKNOWN,
                paymentRepository.findById(ready.getPaymentId()).orElseThrow().getStatus());
        assertEquals(3, paymentHistoryRepository.count());
        PaymentApiLog log = paymentApiLogRepository.findAll().getFirst();
        assertNull(log.getResponseStatus());
        assertFalse(log.getSuccessYn());
        assertEquals("TOSS_COMMUNICATION_ERROR", log.getErrorType());
        assertEquals(0, applicationEvents.stream(PaymentCompletedEvent.class).count());
    }

    /**
     * PG가 승인 오류를 반환하면 FAILED와 오류 이력, API 로그를 함께 저장한다.
     */
    @Test
    void providerRejectionIsRecorded() {
        PaymentResponse ready = paymentController.ready(readyRequest("order_rejected_001", 1500L)).getData();
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.failure(400, "INVALID_REQUEST", "승인 거절"));

        assertThrows(ResponseStatusException.class,
                () -> paymentController.payment(paymentRequest("order_rejected_001", "payment_rejected_001", 1500L)));

        Payment saved = paymentRepository.findById(ready.getPaymentId()).orElseThrow();
        assertEquals(PaymentStatus.FAILED, saved.getStatus());
        assertEquals("INVALID_REQUEST", saved.getFailureCode());
        assertTrue(paymentHistoryRepository.findAll().stream()
                .anyMatch(history -> history.getHistoryType() == PaymentHistoryType.PAYMENT_FAILED));

        PaymentApiLog log = paymentApiLogRepository.findAll().getFirst();
        assertEquals(400, log.getResponseStatus());
        assertFalse(log.getSuccessYn());
        assertEquals("INVALID_REQUEST", log.getErrorType());
        assertEquals(0, applicationEvents.stream(PaymentCompletedEvent.class).count());
    }

    /**
     * 가상계좌 입금 대기는 PROCESSING으로 남기고 완료 이벤트는 발행하지 않는다.
     */
    @Test
    void waitingForDepositDoesNotComplete() {
        PaymentResponse ready = paymentController.ready(readyRequest("order_pending_001", 1500L)).getData();
        TossApiResponse waiting = tossResponse("order_pending_001", "payment_pending_001", 1500L,
                TossPaymentStatus.WAITING_FOR_DEPOSIT);
        when(tossPaymentApi.confirm(any(TossConfirmRequest.class), anyString()))
                .thenReturn(TossApiResult.success(200, waiting));

        PaymentResponse result = paymentController.payment(
                paymentRequest("order_pending_001", "payment_pending_001", 1500L)).getData();

        assertEquals(PaymentStatus.PROCESSING, result.getStatus());
        assertEquals(PaymentStatus.PROCESSING,
                paymentRepository.findById(ready.getPaymentId()).orElseThrow().getStatus());
        assertTrue(paymentHistoryRepository.findAll().stream()
                .anyMatch(history -> history.getHistoryType() == PaymentHistoryType.PAYMENT_PENDING));
        assertEquals(1, paymentApiLogRepository.count());
        assertEquals(0, applicationEvents.stream(PaymentCompletedEvent.class).count());

        assertThrows(ResponseStatusException.class,
                () -> paymentController.ready(readyRequest("order_pending_001", 1500L)));
    }

    /**
     * 준비 API 입력을 만든다.
     */
    private PaymentReadyRequest readyRequest(String orderId, Long amount) {
        PaymentReadyRequest request = new PaymentReadyRequest();
        ReflectionTestUtils.setField(request, "provider", PaymentProvider.TOSS);
        ReflectionTestUtils.setField(request, "orderId", orderId);
        ReflectionTestUtils.setField(request, "amount", amount);
        return request;
    }

    /**
     * 승인 API 입력을 만든다.
     */
    private PaymentRequest paymentRequest(String orderId, String paymentKey, Long amount) {
        PaymentRequest request = new PaymentRequest();
        ReflectionTestUtils.setField(request, "provider", PaymentProvider.TOSS);
        ReflectionTestUtils.setField(request, "orderId", orderId);
        ReflectionTestUtils.setField(request, "paymentKey", paymentKey);
        ReflectionTestUtils.setField(request, "amount", amount);
        return request;
    }

    /**
     * 외부 토스 서버 대신 반환할 Payment 응답을 만든다.
     */
    private TossApiResponse tossResponse(String orderId, String paymentKey, Long amount,
                                         TossPaymentStatus status) {
        TossApiResponse response = new TossApiResponse();
        response.setOrderId(orderId);
        response.setPaymentKey(paymentKey);
        response.setTotalAmount(amount);
        response.setStatus(status);
        response.setMethod(TossPaymentMethod.CARD);
        response.setApprovedAt("2026-11-12T10:00:00+09:00");
        return response;
    }
}
