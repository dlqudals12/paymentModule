package com.api.toss.enums;

/**
 * 토스 Payment 객체의 가상계좌 환불 상태다.
 */
public enum TossRefundStatus {
    NONE,
    PENDING,
    FAILED,
    PARTIAL_FAILED,
    COMPLETED;
}
