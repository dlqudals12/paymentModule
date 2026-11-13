package com.api.toss;

import com.paymentModule.PaymentModuleApplication;
import com.paymentModule.api.toss.TossPaymentApi;
import com.paymentModule.api.toss.dto.request.TossCancelRequest;
import com.paymentModule.api.toss.dto.request.TossConfirmRequest;
import com.paymentModule.api.toss.dto.request.TossVirtualAccountRequest;
import com.paymentModule.api.toss.dto.response.TossApiResponse;
import com.paymentModule.api.toss.dto.response.TossApiResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * 토스 테스트 환경에 직접 요청하는 API 연동 테스트다.
 * 조회는 입력값이 있으면 실행하고, 결제 상태를 바꾸는 테스트는 개별적으로 활성화한다.
 */
@SpringBootTest(classes = PaymentModuleApplication.class)
class TossPaymentApiTest {

    @Autowired
    private TossPaymentApi tossPaymentApi;

    /**
     * 인증된 결제를 승인하고 토스가 반환한 결제 정보를 확인한다.
     * 승인 가능한 paymentKey와 원래 주문번호·금액을 입력해야 한다.
     */
    @Test
    @Disabled("실제 결제 승인이 발생하므로 입력값 설정 후 개별 실행")
    void confirm() {
        String paymentKey = "";
        String orderId = "";
        String amount = "";
        String idempotencyKey = "";

        TossConfirmRequest request = TossConfirmRequest.builder()
                .paymentKey(paymentKey)
                .orderId(orderId)
                .amount(Long.parseLong(amount))
                .build();

        TossApiResult<TossApiResponse> result = tossPaymentApi.confirm(request, idempotencyKey);

        assertSuccess(result);
        assertEquals(paymentKey, result.getData().getPaymentKey());
        assertEquals(orderId, result.getData().getOrderId());
    }

    /**
     * paymentKey로 결제를 조회해 응답의 결제 식별자를 확인한다.
     * 입력값을 채우기 전에는 외부 요청 없이 건너뛴다.
     */
    @Test
    void paymentByKey() {
        String paymentKey = "";

        assumeFalse(paymentKey.isBlank(), "paymentKey 입력 후 실행");

        TossApiResult<TossApiResponse> result = tossPaymentApi.paymentByKey(paymentKey);

        assertSuccess(result);
        assertEquals(paymentKey, result.getData().getPaymentKey());
    }

    /**
     * 상점 주문번호로 결제를 조회해 응답의 주문번호를 확인한다.
     * 이 API를 사용할 수 있는 개별 연동 시크릿 키가 필요하다.
     * 입력값을 채우기 전에는 외부 요청 없이 건너뛴다.
     */
    @Test
    void paymentByOrderId() {
        String orderId = "";

        assumeFalse(orderId.isBlank(), "orderId 입력 후 실행");

        TossApiResult<TossApiResponse> result = tossPaymentApi.paymentByOrderId(orderId);

        assertSuccess(result);
        assertEquals(orderId, result.getData().getOrderId());
    }

    /**
     * 결제를 전액 취소하고 취소 후 상태를 확인한다.
     * 부분 취소를 확인할 때는 요청의 cancelAmount를 별도로 지정한다.
     */
    @Test
    @Disabled("실제 결제 취소가 발생하므로 입력값 설정 후 개별 실행")
    void cancel() {
        String paymentKey = "";
        String cancelReason = "";
        String idempotencyKey = "";

        TossCancelRequest request = TossCancelRequest.builder()
                .cancelReason(cancelReason)
                .build();

        TossApiResult<TossApiResponse> result = tossPaymentApi.cancel(paymentKey, request, idempotencyKey);

        assertSuccess(result);
        assertEquals(paymentKey, result.getData().getPaymentKey());
    }

    /**
     * 새 주문에 가상계좌를 발급하고 응답에 계좌 정보가 있는지 확인한다.
     */
    @Test
    @Disabled("가상계좌 발급이 발생하므로 입력값 설정 후 개별 실행")
    void issueVirtualAccount() {
        Long amount = 0L;
        String orderId = "";
        String orderName = "";
        String customerName = "";
        String bank = "";
        String idempotencyKey = "";

        TossVirtualAccountRequest request = TossVirtualAccountRequest.builder()
                .amount(amount)
                .orderId(orderId)
                .orderName(orderName)
                .customerName(customerName)
                .bank(bank)
                .build();

        TossApiResult<TossApiResponse> result = tossPaymentApi.issueVirtualAccount(request, idempotencyKey);

        assertSuccess(result);
        assertNotNull(result.getData().getVirtualAccount());
    }

    /**
     * HTTP 성공과 Payment 응답 본문이 모두 있는지 확인한다.
     */
    private void assertSuccess(TossApiResult<TossApiResponse> result) {
        assertEquals(200, result.getHttpStatus());
        assertEquals("SUCCESS", result.getCode());
        assertNotNull(result.getData());
    }
}
