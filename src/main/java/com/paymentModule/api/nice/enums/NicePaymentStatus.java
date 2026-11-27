package com.paymentModule.api.nice.enums;

import com.paymentModule.data.enums.PaymentStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 나이스페이 원본 상태와 결제 모듈의 상태를 연결한다.
 */
@Getter
@RequiredArgsConstructor
public enum NicePaymentStatus {
    PAID("paid", PaymentStatus.SUCCESS),
    READY("ready", PaymentStatus.PROCESSING),
    FAILED("failed", PaymentStatus.FAILED),
    CANCELLED("cancelled", PaymentStatus.CANCELLED),
    PARTIAL_CANCELLED("partialCancelled", PaymentStatus.PARTIAL_CANCELLED),
    EXPIRED("expired", PaymentStatus.FAILED);

    private final String value;
    private final PaymentStatus paymentStatus;

    /**
     * 문서에 없는 원본 상태는 승인 여부를 단정하지 않도록 null로 둔다.
     */
    public static NicePaymentStatus of(String value) {
        for (NicePaymentStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) return status;
        }
        return null;
    }
}
