package com.paymentModule.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
    BEFORE("결제 전", null),
    READY("결제 준비", null),
    PROCESSING("결제 처리 중", null),
    SUCCESS("결제 성공", null),
    FAILED("결제 실패", PaymentHistoryType.PAYMENT_FAILED),
    UNKNOWN("결제 결과 미확인", PaymentHistoryType.PAYMENT_UNKNOWN),
    PARTIAL_CANCELLED("부분 취소", PaymentHistoryType.PAYMENT_CANCELED),
    CANCELLED("전체 취소", PaymentHistoryType.PAYMENT_CANCELED);

    private final String value;

    /** PG 실패 또는 결과 미확인 기록에 사용할 이력 유형이다. */
    private final PaymentHistoryType failureHistoryType;
}
