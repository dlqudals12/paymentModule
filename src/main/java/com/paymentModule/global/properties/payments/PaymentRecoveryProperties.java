package com.paymentModule.global.properties.payments;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PG 결제 조회에서 결제 부재 404를 재확인하는 횟수와 간격을 관리한다.
 */
@ConfigurationProperties("payments.recovery")
@Getter
@Setter
@Component
public class PaymentRecoveryProperties {

    private long notFoundRetryDelayMs;
    private int notFoundRetryCount;
}
