package com.paymentModule.global.schedule;

import com.paymentModule.data.enums.OrderStatus;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.data.repository.payment.PaymentRepository;
import com.paymentModule.domain.payment.service.PaymentService;
import com.paymentModule.event.payments.PaymentCompletedEvent;
import com.paymentModule.global.properties.schedule.PaymentSchedulerProperties;
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

class PaymentSchedulerTest {

    /**
     * 재시도 대상이 100건을 넘어도 조회한 모든 결제의 이벤트를 발행한다.
     */
    @Test
    void retriesEveryEligiblePayment() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        PaymentSchedulerProperties properties = new PaymentSchedulerProperties();
        properties.setOrder(new PaymentSchedulerProperties.Order(300_000L, 60_000L, 600_000L));

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

        new PaymentScheduler(paymentRepository, paymentService, eventPublisher, properties).retryOrder();

        verify(eventPublisher, times(101)).publishEvent(any(PaymentCompletedEvent.class));
    }

    /**
     * 10분 이상 지난 처리 중·결과 미확인 결제를 모두 복구 서비스에 전달한다.
     */
    @Test
    void recoversEveryUnresolvedPayment() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentService paymentService = mock(PaymentService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        PaymentSchedulerProperties properties = new PaymentSchedulerProperties();
        properties.setProcessing(new PaymentSchedulerProperties.Processing(300_000L, 60_000L, 600_000L));

        Payment processing = Payment.builder().id(1L).paymentStatus(PaymentStatus.PROCESSING).build();
        Payment unknown = Payment.builder().id(2L).paymentStatus(PaymentStatus.UNKNOWN).build();
        Payment waiting = Payment.builder().id(3L).paymentStatus(PaymentStatus.WAITING_FOR_PAYMENT).build();
        when(paymentRepository.findProcessingRecoveryCandidates(
                eq(PaymentStatus.recoveryTargets()), any(LocalDateTime.class)))
                .thenReturn(List.of(processing, unknown, waiting));

        new PaymentScheduler(paymentRepository, paymentService, eventPublisher, properties).recoverProcessing();

        verify(paymentService).recover(processing);
        verify(paymentService).recover(unknown);
        verify(paymentService).recover(waiting);
    }
}
