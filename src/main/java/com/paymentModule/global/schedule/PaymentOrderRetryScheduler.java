package com.paymentModule.global.schedule;

import com.paymentModule.data.enums.OrderStatus;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.data.repository.payment.PaymentRepository;
import com.paymentModule.event.payments.PaymentCompletedEvent;
import com.paymentModule.global.properties.schedule.PaymentOrderRetryScheduleProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class PaymentOrderRetryScheduler {

    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentOrderRetryScheduleProperties scheduleProperties;

    /**
     * 결제 성공 후 주문 반영이 끝나지 않은 건의 완료 이벤트를 다시 전달한다.
     */
    @Scheduled(fixedDelayString = "#{@paymentOrderRetryScheduleProperties.retryDelayMs}",
            initialDelayString = "#{@paymentOrderRetryScheduleProperties.retryInitialDelayMs}")
    public void retry() {
        //PENDING 전환 직후의 주문 반영 작업과 재전달이 겹치지 않도록 설정된 유예 시간을 둔다.
        LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofMillis(scheduleProperties.getRetryMinAgeMs()));

        List<Payment> payments = paymentRepository.findOrderRetryCandidates(
                PaymentStatus.SUCCESS, OrderStatus.BEFORE, OrderStatus.PENDING, cutoff);

        for (Payment payment : payments) {
            try {
                eventPublisher.publishEvent(new PaymentCompletedEvent(
                        payment.getId(), payment.getProvider(), payment.getOrderId(), payment.getAmount()));
            } catch (RuntimeException exception) {
                log.error("Order completion event retry failed: paymentId={}", payment.getId(), exception);
            }
        }
    }
}
