package com.paymentModule.domain.payment.processor;

import lombok.Builder;
import lombok.Getter;

/**
 * 간편결제 제공사와 간편결제 사용 금액을 담는다.
 */
@Getter
@Builder
public class PaymentEasyPayInfo {
    private String provider;
    private Long amount;
    private Long discountAmount;
}
