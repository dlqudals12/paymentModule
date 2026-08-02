package com.paymentModule.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentApiType {
    PAYMENT("결제"),
    CANCEL("취소"),
    BILLING_KEY("빌링키 발급"),
    SUBSCRIPTION_PAYMENT("정기 결제"),
    PAYMENT_LOOKUP("결제 조회");

    private final String value;
}
