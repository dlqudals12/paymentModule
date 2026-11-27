package com.paymentModule.domain.payment.dto.request;

import com.paymentModule.data.enums.PaymentProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentRequest {

    @NotNull
    private PaymentProvider provider;

    @NotBlank
    @Size(max = 64)
    private String orderId;

    @Size(max = 200)
    private String externalPaymentId;

    @NotNull
    @Positive
    private Long amount;

    // NICEPAY Server 승인 방식의 SDK 인증 결과다. 토스 요청에서는 사용하지 않는다.
    private String clientId;
    private String authToken;
    private String signature;
}
