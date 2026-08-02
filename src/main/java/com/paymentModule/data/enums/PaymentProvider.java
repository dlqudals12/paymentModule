package com.paymentModule.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentProvider {
    TOSS("토스페이먼츠"),
    KCP("NHN KCP"),
    PORTONE("포트원");

    private final String value;
}
