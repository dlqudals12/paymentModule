package com.paymentModule.domain.payment.processor;

import com.paymentModule.data.enums.OrderStatus;
import com.paymentModule.data.enums.PaymentMethodType;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.enums.PaymentType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * PG 처리기에 전달하는 결제 행의 읽기 전용 값이다. JPA 연관 객체는 포함하지 않는다.
 */
@Getter
@Builder
public class PaymentProcessorData {
    private final Long id;
    private final Long userId;
    private final String loginId;
    private final String orderId;
    private final String activeOrderId;
    private final String externalPaymentId;
    private final PaymentProvider provider;
    private final PaymentType paymentType;
    private final PaymentMethodType paymentMethod;
    private final PaymentStatus paymentStatus;
    private final LocalDateTime paymentStatusChangedAt;
    private final OrderStatus orderStatus;
    private final LocalDateTime orderStatusChangedAt;
    private final Long amount;
    private final Long cancelledAmount;
    private final String idempotencyKey;
    private final String failureCode;
    private final String failureMessage;
    private final LocalDateTime requestedAt;
    private final LocalDateTime approvedAt;
    private final LocalDateTime cancelledAt;
    private final String createdBy;
    private final LocalDateTime createdAt;
    private final String updatedBy;
    private final LocalDateTime updatedAt;
}
