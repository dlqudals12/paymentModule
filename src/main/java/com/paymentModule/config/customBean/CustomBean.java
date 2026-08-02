package com.paymentModule.config.customBean;

import com.paymentModule.domain.payment.processor.PaymentProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomBean {

    @Bean
    public PaymentProcessor paymentProcessor() {
        return null;
    }
}
