package com.paymentModule.domain.payment.event;

import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.domain.payment.service.PaymentTransactionService;
import com.paymentModule.event.payments.PaymentCompletedEvent;
import com.paymentModule.event.payments.PaymentCompletedEventListener;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class PaymentCompletedEventListenerTest {

    private final PaymentTransactionService paymentTransactionService = mock(PaymentTransactionService.class);
    private final PaymentCompletedEventListener listener =
            new PaymentCompletedEventListener(paymentTransactionService);

    /**
     * 완료 이벤트를 받으면 주문 반영 대기 상태까지만 기록한다.
     */
    @Test
    void completedPaymentRemainsPending() {
        PaymentCompletedEvent event = new PaymentCompletedEvent(1L, PaymentProvider.TOSS, "order_001", 10_000L);
        when(paymentTransactionService.updateOrderPending(1L)).thenReturn(true);
        listener.complete(event);

        verify(paymentTransactionService).updateOrderPending(1L);
        verify(paymentTransactionService, never()).updateOrderComplete(1L);
    }

    /**
     * 완료됐거나 결제 성공 상태가 아니면 후속 상태를 변경하지 않는다.
     */
    @Test
    void ineligiblePaymentIsIgnored() {
        PaymentCompletedEvent event = new PaymentCompletedEvent(2L, PaymentProvider.TOSS, "order_002", 10_000L);
        when(paymentTransactionService.updateOrderPending(2L)).thenReturn(false);

        listener.complete(event);

        verify(paymentTransactionService).updateOrderPending(2L);
        verify(paymentTransactionService, never()).updateOrderComplete(2L);
    }
}
