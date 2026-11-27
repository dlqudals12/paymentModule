package com.paymentModule.domain.payment.processor;

import com.paymentModule.data.enums.PaymentMethodType;
import com.paymentModule.data.enums.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PG별 응답을 결제 서비스가 처리할 수 있는 공통 값으로 변환한 결과다.
 */
@Getter
@Builder
public class PaymentProcessResult {
    /** PG API 호출별 로그 값이다. 여러 호출을 수행했다면 실제 실행 순서대로 담는다. */
    private List<PaymentApiCallResult> apiCalls;
    private Integer httpStatus;
    private PaymentStatus status;
    private String externalPaymentId;
    private String transactionId;
    /** PG 가맹점 식별자다. 토스 mId, 나이스 MID에 해당한다. */
    private String merchantId;
    private PaymentMethodType paymentMethod;
    /** PG 응답의 원본 상태와 결제수단 값이다. 공통 enum으로 변환되지 않은 값도 확인할 수 있다. */
    private String providerStatus;
    private String providerMethod;
    /** PG가 기록한 결제 요청 시각이다. API 호출 시각은 apiCalls에 별도로 남긴다. */
    private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private String receiptUrl;
    /** 결제수단에 해당하는 상세 정보만 채운다. PG가 제공하지 않는 값은 null이다. */
    private PaymentCardInfo card;
    private PaymentTransferInfo transfer;
    private PaymentVirtualAccountInfo virtualAccount;
    private PaymentEasyPayInfo easyPay;
    private PaymentCashReceiptInfo cashReceipt;
    private String code;
    private String message;
}
