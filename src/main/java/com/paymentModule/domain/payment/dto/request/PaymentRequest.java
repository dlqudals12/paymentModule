package com.paymentModule.domain.payment.dto.request;

import com.paymentModule.data.enums.PaymentProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class PaymentRequest {

    @NotNull
    private PaymentProvider provider;

    @NotBlank
    @Size(max = 64)
    private String orderId;

    @Size(max = 200)
    private String paymentKey;

    @NotNull
    @Positive
    private Long amount;
}
