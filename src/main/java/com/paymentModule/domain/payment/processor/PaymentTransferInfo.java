package com.paymentModule.domain.payment.processor;

import lombok.Builder;
import lombok.Getter;

/**
 * 계좌이체에 사용한 은행과 승인 정보를 담는다.
 */
@Getter
@Builder
public class PaymentTransferInfo {
    private String bankCode;
    private String bankName;
    private String approvalNumber;
}
