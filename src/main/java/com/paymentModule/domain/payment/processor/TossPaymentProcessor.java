package com.paymentModule.domain.payment.processor;

import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;

public class TossPaymentProcessor implements PaymentProcessor {

    @Override
    public Payment payment(PaymentRequest paymentRequest) {
        return null;
    }
}
