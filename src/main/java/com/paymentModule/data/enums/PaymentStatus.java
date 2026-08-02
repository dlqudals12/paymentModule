package com.paymentModule.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
    READY("결제 준비"),
    PROCESSING("결제 처리 중"),
    SUCCESS("결제 성공"),
    FAILED("결제 실패"),
    UNKNOWN("결제 결과 미확인"),
    PARTIAL_CANCELLED("부분 취소"),
    CANCELLED("전체 취소");

    private final String value;
}
