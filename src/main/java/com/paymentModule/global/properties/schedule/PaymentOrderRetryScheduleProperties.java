package com.paymentModule.global.properties.schedule;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 결제 완료 후 주문 반영 재시도 주기를 관리한다.
 */
@ConfigurationProperties("schedule.payments.order")
@Getter
@Setter
@Component
public class PaymentOrderRetryScheduleProperties {

    private long retryDelayMs;
    private long retryInitialDelayMs;
    private long retryMinAgeMs;
}
