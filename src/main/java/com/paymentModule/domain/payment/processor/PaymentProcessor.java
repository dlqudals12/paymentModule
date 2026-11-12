package com.paymentModule.domain.payment.processor;

import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import org.springframework.http.HttpMethod;

/**
 * PG별 승인 요청을 공통 결제 결과로 변환하는 계약이다.
 */
public interface PaymentProcessor {
    /**
     * 결제 API 주소
     */
    String paymentUrl();

    /**
     * 결제 API HTTP 메서드
     */
    HttpMethod requestMethod();

    /**
     * PG별 주문 식별자 형식을 결제 준비 전에 검증한다.
     */
    void validateReady(PaymentReadyRequest request);

    /**
     * PG별 필수 인증값을 외부 승인 요청 전에 검증한다.
     */
    void validate(PaymentRequest request);

    /**
     * 저장된 결제 정보로 PG 승인을 요청하고 결과를 반환한다.
     */
    PaymentProcessResult payment(Payment payment, PaymentRequest request);
}
