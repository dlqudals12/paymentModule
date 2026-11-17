package com.paymentModule.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
    BEFORE("결제 전", null, false),
    READY("결제 준비", PaymentHistoryType.CREATED, false),
    PROCESSING("결제 처리 중", PaymentHistoryType.PAYMENT_PENDING, true),
    WAITING_FOR_PAYMENT("결제 완료 대기", PaymentHistoryType.PAYMENT_PENDING, true),
    SUCCESS("결제 성공", PaymentHistoryType.PAYMENT_SUCCEEDED, false),
    FAILED("결제 실패", PaymentHistoryType.PAYMENT_FAILED, false),
    UNKNOWN("결제 결과 미확인", PaymentHistoryType.PAYMENT_UNKNOWN, true),
    PARTIAL_CANCELLED("부분 취소", PaymentHistoryType.PAYMENT_CANCELED, false),
    CANCELLED("전체 취소", PaymentHistoryType.PAYMENT_CANCELED, false);

    private final String value;

    /** 결제 생성 또는 PG 결과 저장 시 상태에 대응하는 이력 유형이다. */
    private final PaymentHistoryType paymentHistoryType;

    /** PG 결과를 다시 조회할 수 있는 미확정 상태인지 나타낸다. */
    private final boolean recoveryTarget;

    /**
     * PG 결과 재조회 대상 상태를 반환한다.
     */
    public static List<PaymentStatus> recoveryTargets() {
        return Arrays.stream(values())
                .filter(PaymentStatus::isRecoveryTarget)
                .toList();
    }
}
