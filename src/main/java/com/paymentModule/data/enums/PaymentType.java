package com.paymentModule.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentType {
    ONE_TIME("일회성 결제"),
    SUBSCRIPTION("정기 결제");

    private final String value;
}
