package com.paymentModule.event.payments;

import com.paymentModule.domain.payment.service.PaymentTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletedEventListener {

    private final PaymentTransactionService paymentTransactionService;

    /**
     * 결제 완료 이벤트를 받아 주문 반영 상태를 대기 중으로 변경한다.
     */
    @Async
    @EventListener
    public void complete(PaymentCompletedEvent event) {
        try {
            //이벤트 수신 시 BEFORE를 PENDING으로 변경한다. 재전달된 PENDING도 처리한다.
            if (!paymentTransactionService.updateOrderPending(event.paymentId())) {
                return;
            }

            // TODO: 수신 측에서 주문·상품 반영을 별도 트랜잭션으로 완료한 뒤
            // updateOrderComplete를 호출한다. 실제 반영 전에는 COMPLETE로 변경하지 않는다.
        } catch (RuntimeException exception) {
            //실패 시 PENDING을 유지해 스케줄러가 이벤트를 다시 전달할 수 있게 한다.
            log.error("Order completion failed: paymentId={}", event.paymentId(), exception);
        }
    }
}
