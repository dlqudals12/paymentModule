package com.paymentModule.global.properties.payments;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 진행 중인 PG 요청을 조회했을 때 미조회 결과를 확정하기까지의 유예 시간을 관리한다.
 */
@ConfigurationProperties("payments.recovery")
@Getter
@Setter
@Component
public class PaymentRecoveryProperties {

    private long notFoundGraceMs;
}
