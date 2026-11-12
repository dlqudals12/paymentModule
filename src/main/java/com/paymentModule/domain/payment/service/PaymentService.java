package com.paymentModule.domain.payment.service;

import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.domain.payment.dto.response.PaymentResponse;
import com.paymentModule.domain.payment.event.PaymentCompletedEvent;
import com.paymentModule.domain.payment.processor.PaymentProcessResult;
import com.paymentModule.domain.payment.processor.PaymentProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentTransactionService paymentTransactionService;
    private final Map<PaymentProvider, PaymentProcessor> paymentProcessors;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 결제 준비
     */
    public PaymentResponse register(PaymentReadyRequest request) {
        //PG별 주문번호 검증
        processor(request.getProvider()).validateReady(request);

        // 현재는 전달된 금액을 저장하며 결제 모듈에서 상품 가격을 계산하지 않는다.
        try {
            return paymentTransactionService.register(request);
        } catch (DataIntegrityViolationException exception) {
            //동시 ready 요청이 모두 신규로 조회됐으면 DB 고유 제약이 하나를 막는다.
            //첫 트랜잭션이 커밋된 뒤 기존 READY를 다시 조회해 재호출로 처리한다.
            return paymentTransactionService.register(request);
        }
    }

    /**
     * 결제 승인
     */
    public PaymentResponse payment(PaymentRequest request) {
        //PG 처리기 선택 및 승인값 검증
        PaymentProcessor processor = processor(request.getProvider());
        processor.validate(request);

        //승인 요청 전 상태를 별도 트랜잭션으로 저장
        Payment payment = paymentTransactionService.updateProcessing(request);
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return PaymentResponse.of(payment);
        }

        //PG 요청 시간과 응답 소요 시간을 API 로그에 남긴다.
        LocalDateTime requestedAt = LocalDateTime.now();
        long startedAt = System.nanoTime();

        PaymentProcessResult result;
        try {
            //PG 승인 요청
            result = Objects.requireNonNull(processor.payment(payment, request));
            Objects.requireNonNull(result.getStatus(), "PG 결제 결과에 상태가 없습니다.");
        } catch (RuntimeException exception) {
            //외부 요청 이후 예외가 발생하면 승인 여부를 실패로 단정할 수 없다.
            PaymentProcessResult unknown = PaymentProcessResult.builder()
                    .status(PaymentStatus.UNKNOWN)
                    .code("PG_UNEXPECTED_ERROR")
                    .message("PG 승인 호출 중 예외가 발생했습니다.")
                    .build();

            paymentTransactionService.updateFailure(payment, processor, unknown,
                    requestedAt, elapsedMillis(startedAt), exception);
            throw exception;
        }

        //PG 승인 실패와 결과 미확인 상태를 결제, 이력, API 로그에 함께 저장
        if (result.getStatus() != PaymentStatus.SUCCESS
                && result.getStatus() != PaymentStatus.PROCESSING) {
            paymentTransactionService.updateFailure(payment, processor, result,
                    requestedAt, elapsedMillis(startedAt), null);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, result.getMessage());
        }

        //PG 응답을 받은 뒤 새 트랜잭션에서 최종 결제 상태 저장
        PaymentResponse paymentResponse = paymentTransactionService.updateResult(payment, processor,
                result, requestedAt, elapsedMillis(startedAt));

        //결제 성공 커밋 후 이벤트 발행, 후속 업무의 비동기 실행은 수신 측에서 설정
        if (paymentResponse.getStatus() == PaymentStatus.SUCCESS) {
            try {
                eventPublisher.publishEvent(new PaymentCompletedEvent(
                        paymentResponse.getPaymentId(), paymentResponse.getProvider(),
                        paymentResponse.getOrderId(), paymentResponse.getAmount()));
            } catch (RuntimeException exception) {
                log.error("Payment completion event publication failed: paymentId={}",
                        paymentResponse.getPaymentId(), exception);
            }
        }

        return paymentResponse;
    }

    /**
     * PG 처리기 선택
     */
    private PaymentProcessor processor(PaymentProvider provider) {
        PaymentProcessor processor = paymentProcessors.get(provider);
        if (processor == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 결제대행사입니다.");
        }

        return processor;
    }

    /**
     * PG 요청 소요 시간 계산
     */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
