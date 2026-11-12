package com.api.toss.enums;

/**
 * 토스 Payment 객체의 카드 매입 상태다.
 */
public enum TossCardAcquireStatus {
    READY,
    REQUESTED,
    COMPLETED,
    CANCEL_REQUESTED,
    CANCELED;
}
