package com.paymentModule.domain.payment.processor;

import lombok.Builder;
import lombok.Getter;

/**
 * PG별 카드 승인 정보를 공통 필드로 변환한 결과다.
 */
@Getter
@Builder
public class PaymentCardInfo {
    private String issuerCode;
    private String issuerName;
    private String acquirerCode;
    private String acquirerName;
    /** PG가 일부 숫자를 가려서 제공한 카드번호만 담는다. */
    private String maskedNumber;
    private Integer installmentMonths;
    private String approvalNumber;
    private Boolean interestFree;
    private String cardType;
    private String ownerType;
    private Boolean cardPointUsed;
}
