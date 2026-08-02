package com.paymentModule.domain.payment.processor;

import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;

public interface PaymentProcessor {
    Payment payment(PaymentRequest paymentRequest);
}
