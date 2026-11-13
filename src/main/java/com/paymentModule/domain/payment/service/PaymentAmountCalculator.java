package com.paymentModule.domain.payment.service;

import org.springframework.stereotype.Component;

@Component
public class PaymentAmountCalculator {

    /**
     * 주문 식별자로 서버에서 확정한 결제 금액을 계산한다.
     */
    public Long calculate(String orderId) {
        // TODO: 주문 테이블 또는 주문 서비스에서 orderId에 해당하는 주문을 조회하고 금액을 계산한다.
        return 10_000L;
    }
}
