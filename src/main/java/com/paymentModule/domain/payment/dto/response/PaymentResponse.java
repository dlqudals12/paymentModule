package com.paymentModule.domain.payment.dto.response;

import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.model.entity.payment.Payment;
import lombok.Builder;
import lombok.Getter;

/**
 * 클라이언트가 SDK 요청과 결제 결과 확인에 사용하는 정보다.
 */
@Getter
@Builder
public class PaymentResponse {
    private Long paymentId;
    private PaymentProvider provider;
    private String orderId;
    private Long amount;
    private PaymentStatus status;

    /**
     * 결제 엔티티를 API 응답으로 변환
     */
    public static PaymentResponse of(Payment payment) {
        return PaymentResponse.builder()
                .paymentId(payment.getId())
                .provider(payment.getProvider())
                .orderId(payment.getOrderId())
                .amount(payment.getAmount())
                .status(payment.getStatus())
                .build();
    }
}
