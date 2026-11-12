package com.paymentModule.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentHistoryType {
    CREATED("결제 생성"),
    PAYMENT_REQUESTED("결제 요청"),
    PAYMENT_PENDING("결제 대기"),
    PAYMENT_SUCCEEDED("결제 성공"),
    PAYMENT_FAILED("결제 실패"),
    PAYMENT_UNKNOWN("결제 결과 미확인"),
    PAYMENT_CANCELED("결제 취소 상태 확인"),
    CANCEL_REQUESTED("취소 요청"),
    CANCEL_SUCCEEDED("취소 성공"),
    CANCEL_FAILED("취소 실패");

    private final String value;
}
