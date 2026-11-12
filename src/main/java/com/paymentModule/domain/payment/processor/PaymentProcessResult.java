package com.paymentModule.domain.payment.processor;

import com.paymentModule.data.enums.PaymentMethod;
import com.paymentModule.data.enums.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * PG별 응답을 결제 서비스가 처리할 수 있는 공통 값으로 변환한 결과다.
 */
@Getter
@Builder
public class PaymentProcessResult {
    private Integer httpStatus;
    private PaymentStatus status;
    private String transactionId;
    private PaymentMethod paymentMethod;
    private LocalDateTime approvedAt;
    private String code;
    private String message;
}
