package com.paymentModule.domain.payment.dto.request;

import com.paymentModule.data.enums.PaymentProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/**
 * 결제 준비 단계에서 서버가 확정한 식별자와 금액이다.
 */
@Getter
public class PaymentReadyRequest {
    @NotNull
    private PaymentProvider provider;

    @NotBlank
    @Size(max = 64)
    private String orderId;

    @NotNull
    @Positive
    private Long amount;
}
