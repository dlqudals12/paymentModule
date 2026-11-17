package com.paymentModule.global.properties.schedule;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 결제 상태 복구와 주문 반영 재시도 주기를 관리한다.
 */
@ConfigurationProperties("schedule.payments")
@Getter
@Setter
@Component
public class PaymentSchedulerProperties {

    private Order order;
    private Processing processing;

    /**
     * 결제 완료 후 주문 반영 이벤트를 재전달하는 일정이다.
     */
    public record Order(long retryDelayMs, long retryInitialDelayMs, long retryMinAgeMs) {
    }

    /**
     * 결과 미확정 결제를 PG 조회로 복구하는 일정이다.
     */
    public record Processing(long retryDelayMs, long retryInitialDelayMs, long retryMinAgeMs) {
    }
}
