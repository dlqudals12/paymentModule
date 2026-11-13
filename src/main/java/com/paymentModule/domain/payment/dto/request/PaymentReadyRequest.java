package com.paymentModule.domain.payment.dto.request;

import com.paymentModule.data.enums.PaymentProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/**
 * 결제 준비 단계에서 서버가 확인할 PG와 주문 식별자다.
 */
@Getter
public class PaymentReadyRequest {
    @NotNull
    private PaymentProvider provider;

    @NotBlank
    @Size(max = 64)
    private String orderId;
}
