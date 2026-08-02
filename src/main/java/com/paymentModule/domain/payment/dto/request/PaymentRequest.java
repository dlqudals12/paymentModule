package com.paymentModule.domain.payment.dto.request;

import com.paymentModule.data.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;

@Getter
public class PaymentRequest {

    @NotNull
    private Long productId;

    @NotNull
    private PaymentMethod paymentMethod;

    @NotNull
    String idempotencyKey;

    @NotNull
    @Positive
    private Long amount;
}
