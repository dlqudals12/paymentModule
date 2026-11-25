package com.paymentModule.domain.payment.processor;

import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;

/**
 * PG별 승인 요청을 공통 결제 결과로 변환하는 계약이다.
 */
public interface PaymentProcessor {
    /**
     * PG 타입
     */
    PaymentProvider provider();

    /**
     * PG별 주문 식별자 형식을 결제 준비 전에 검증한다.
     */
    void orderIdValidation(PaymentReadyRequest request);

    /**
     * PG별 필수 인증값을 외부 승인 요청 전에 검증한다.
     */
    void paymentValidation(PaymentRequest request);

    /**
     * 저장된 결제 정보로 PG 승인을 요청한다. API 오류는 PaymentException으로 전달한다.
     */
    PaymentProcessResult payment(PaymentProcessorData payment, PaymentRequest request);

    /**
     * 저장된 결제 키로 실제 PG 결과를 조회한다. API 오류는 PaymentException으로 전달한다.
     */
    PaymentProcessResult lookup(PaymentProcessorData payment, String externalPaymentId);

    default int priority() {
        return 1;
    }

    /**
     * Registry 등록 여부
     */
    default boolean isRegistry() {
        return true;
    }
}
