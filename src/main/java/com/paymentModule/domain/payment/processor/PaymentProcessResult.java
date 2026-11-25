package com.paymentModule.domain.payment.processor;

import com.paymentModule.data.enums.PaymentMethod;
import com.paymentModule.data.enums.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PG별 응답을 결제 서비스가 처리할 수 있는 공통 값으로 변환한 결과다.
 */
@Getter
@Builder
public class PaymentProcessResult {
    /** PG API 호출별 로그 값이다. 여러 호출을 수행했다면 실제 실행 순서대로 담는다. */
    private List<PaymentApiCallResult> apiCalls;
    private Integer httpStatus;
    private PaymentStatus status;
    private String externalPaymentId;
    private String transactionId;
    private PaymentMethod paymentMethod;
    private LocalDateTime approvedAt;
    private String code;
    private String message;
}
