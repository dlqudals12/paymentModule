package com.paymentModule.domain.payment.processor;

import com.paymentModule.data.enums.PaymentProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Component
public class PaymentRegistry {

    private final Map<PaymentProvider, PaymentProcessor> paymentProcessors;

    public PaymentRegistry(TossPaymentProcessor tossPaymentProcessor) {
        this.paymentProcessors = Map.of(PaymentProvider.TOSS, tossPaymentProcessor);
        ;
    }

    /**
     * PG 처리기 선택
     */
    public PaymentProcessor processor(PaymentProvider provider) {
        PaymentProcessor processor = paymentProcessors.get(provider);

        if (processor == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 결제대행사입니다.");
        }

        return processor;
    }
}
