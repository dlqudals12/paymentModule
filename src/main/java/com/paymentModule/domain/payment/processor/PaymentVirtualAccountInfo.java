package com.paymentModule.domain.payment.processor;

import lombok.Builder;
import lombok.Getter;

/**
 * 입금 대기 중 고객에게 안내할 가상계좌 정보를 담는다.
 */
@Getter
@Builder
public class PaymentVirtualAccountInfo {
    private String bankCode;
    private String bankName;
    private String accountNumber;
    private String accountHolderName;
    private String depositorName;
    private String dueDate;
    private Boolean expired;
}
