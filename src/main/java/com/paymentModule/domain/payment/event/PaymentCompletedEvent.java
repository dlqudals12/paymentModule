package com.paymentModule.domain.payment.event;

import com.paymentModule.data.enums.PaymentProvider;

/**
 * 결제 모듈 밖의 업무가 후속 처리를 시작할 때 사용하는 도메인 중립 이벤트다.
 * 수신 측은 비동기 리스너에서 별도 트랜잭션으로 후속 업무를 처리한다.
 */
public record PaymentCompletedEvent(Long paymentId, PaymentProvider provider, String orderId, Long amount) {
}
