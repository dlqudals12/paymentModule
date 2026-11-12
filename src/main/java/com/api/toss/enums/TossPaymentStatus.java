package com.api.toss.enums;

import com.paymentModule.data.enums.PaymentStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 토스 Payment 객체의 결제 처리 상태다.
 */
@Getter
@RequiredArgsConstructor
public enum TossPaymentStatus {
    READY(PaymentStatus.READY),
    IN_PROGRESS(PaymentStatus.PROCESSING),
    WAITING_FOR_DEPOSIT(PaymentStatus.PROCESSING),
    DONE(PaymentStatus.SUCCESS),
    CANCELED(PaymentStatus.CANCELLED),
    PARTIAL_CANCELED(PaymentStatus.PARTIAL_CANCELLED),
    ABORTED(PaymentStatus.FAILED),
    EXPIRED(PaymentStatus.FAILED);

    private final PaymentStatus paymentStatus;
}
