package com.paymentModule.domain.payment.processor;

import com.paymentModule.data.enums.PaymentProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentRegistry {

    private final Map<PaymentProvider, PaymentProcessor> paymentProcessors;

    public PaymentRegistry(List<PaymentProcessor> processors) {
        HashMap<PaymentProvider, PaymentProcessor> paymentProcessors = new HashMap<>();

        for (PaymentProcessor processor : processors) {
            PaymentProvider provider = processor.provider();

            //registry에 등록하지 않거나 등록하려는 프로세서가 중복인데 우선순위가 같거나 낮으면 건너뜀
            if (!processor.isRegistry() || (paymentProcessors.containsKey(provider)
                    && paymentProcessors.get(provider).importantPoint() > processor.importantPoint())) {
                continue;
            }

            paymentProcessors.put(provider, processor);
        }

        this.paymentProcessors = paymentProcessors;
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
