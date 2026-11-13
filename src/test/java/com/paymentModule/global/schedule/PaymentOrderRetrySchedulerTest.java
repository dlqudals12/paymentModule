package com.paymentModule.global.schedule;

import com.paymentModule.data.enums.OrderStatus;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.data.repository.payment.PaymentRepository;
import com.paymentModule.event.payments.PaymentCompletedEvent;
import com.paymentModule.global.properties.schedule.PaymentOrderRetryScheduleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentOrderRetrySchedulerTest {

    /**
     * 재시도 대상이 100건을 넘어도 조회한 모든 결제의 이벤트를 발행한다.
     */
    @Test
    void retriesEveryEligiblePayment() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        PaymentOrderRetryScheduleProperties properties = new PaymentOrderRetryScheduleProperties();
        properties.setRetryMinAgeMs(600_000L);

        List<Payment> payments = IntStream.range(0, 101)
                .mapToObj(index -> Payment.builder()
                        .id((long) index + 1)
                        .provider(PaymentProvider.TOSS)
                        .orderId("order_retry_" + index)
                        .amount(10_000L)
                        .build())
                .toList();
        when(paymentRepository.findOrderRetryCandidates(
                eq(PaymentStatus.SUCCESS), eq(OrderStatus.BEFORE), eq(OrderStatus.PENDING),
                any(LocalDateTime.class))).thenReturn(payments);

        new PaymentOrderRetryScheduler(paymentRepository, eventPublisher, properties).retry();

        verify(eventPublisher, times(101)).publishEvent(any(PaymentCompletedEvent.class));
    }
}
