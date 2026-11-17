package com.paymentModule.global.schedule;

import com.paymentModule.data.enums.OrderStatus;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.data.repository.payment.PaymentRepository;
import com.paymentModule.domain.payment.service.PaymentService;
import com.paymentModule.event.payments.PaymentCompletedEvent;
import com.paymentModule.global.properties.schedule.PaymentSchedulerProperties;
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
public class PaymentScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentSchedulerProperties scheduleProperties;

    /**
     * 결제 성공 후 주문 반영이 끝나지 않은 건의 완료 이벤트를 다시 전달한다.
     */
    @Scheduled(fixedDelayString = "#{@paymentSchedulerProperties.getOrder().retryDelayMs()}",
            initialDelayString = "#{@paymentSchedulerProperties.getOrder().retryInitialDelayMs()}")
    public void retryOrder() {
        //PENDING 전환 직후의 주문 반영 작업과 재전달이 겹치지 않도록 설정된 유예 시간을 둔다.
        LocalDateTime cutoff = LocalDateTime.now().minus(
                Duration.ofMillis(scheduleProperties.getOrder().retryMinAgeMs()));

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

    /**
     * 오래된 처리 중·결과 미확인·결제 대기 상태를 PG 조회로 복구한다.
     */
    @Scheduled(fixedDelayString = "#{@paymentSchedulerProperties.getProcessing().retryDelayMs()}",
            initialDelayString = "#{@paymentSchedulerProperties.getProcessing().retryInitialDelayMs()}")
    public void recoverProcessing() {
        //PG 승인 호출이나 웹훅 반영 중인 결제를 곧바로 재조회하지 않도록 10분 유예를 둔다.
        LocalDateTime cutoff = LocalDateTime.now().minus(
                Duration.ofMillis(scheduleProperties.getProcessing().retryMinAgeMs()));

        List<Payment> payments = paymentRepository.findProcessingRecoveryCandidates(
                List.of(PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN,
                        PaymentStatus.WAITING_FOR_PAYMENT), cutoff);

        for (Payment payment : payments) {
            try {
                //한 건의 조회 실패가 나머지 결제 복구를 막지 않도록 건별로 처리한다.
                paymentService.recover(payment);
            } catch (RuntimeException exception) {
                log.error("Payment status recovery failed: paymentId={}", payment.getId(), exception);
            }
        }
    }
}
