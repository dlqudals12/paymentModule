package com.api.toss.enums;

/**
 * 토스 Payment 객체의 결제 처리 상태다.
 */
public enum TossPaymentStatus {
    READY,
    IN_PROGRESS,
    WAITING_FOR_DEPOSIT,
    DONE,
    CANCELED,
    PARTIAL_CANCELED,
    ABORTED,
    EXPIRED;
}
