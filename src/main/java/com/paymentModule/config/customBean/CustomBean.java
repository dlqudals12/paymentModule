package com.paymentModule.config.customBean;

import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.domain.payment.processor.PaymentProcessor;
import com.paymentModule.domain.payment.processor.TossPaymentProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Map;

@Configuration
@EnableAsync
public class CustomBean {

    /**
     * 요청한 PG에 맞는 결제 처리기를 서비스가 선택할 수 있도록 등록한다.
     */
    @Bean
    public Map<PaymentProvider, PaymentProcessor> paymentProcessors(TossPaymentProcessor tossPaymentProcessor) {
        return Map.of(PaymentProvider.TOSS, tossPaymentProcessor);
    }
}
