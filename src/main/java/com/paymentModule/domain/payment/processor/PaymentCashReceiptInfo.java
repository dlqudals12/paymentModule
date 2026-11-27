package com.paymentModule.domain.payment.processor;

import lombok.Builder;
import lombok.Getter;

/**
 * 현금영수증 발급 결과를 공통 필드로 담는다.
 */
@Getter
@Builder
public class PaymentCashReceiptInfo {
    private String type;
    private String issueNumber;
    private String receiptUrl;
    private String issueStatus;
}
