package com.paymentModule.domain.payment.service;

import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentApiType;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.domain.payment.dto.response.PaymentResponse;
import com.paymentModule.domain.payment.processor.PaymentProcessResult;
import com.paymentModule.domain.payment.processor.PaymentProcessor;
import com.paymentModule.event.payments.PaymentCompletedEvent;
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

        //주문 식별자로 금액을 계산하는 로직은 트랜잭션 서비스에서 호출한다.
        try {
            return paymentTransactionService.register(request);
        } catch (DataIntegrityViolationException exception) {
            //동시 ready 생성 충돌로 이전 트랜잭션이 롤백된 뒤 새 트랜잭션에서 재조회한다.
            //트랜잭션 내부에서 같은 예외를 잡아 재시도하면 롤백 상태를 재사용하게 된다.
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
        Payment payment;
        try {
            payment = paymentTransactionService.updateProcessing(request);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() != HttpStatus.CONFLICT) {
                throw exception;
            }

            //이미 처리 중인 시도만 PG 조회로 확인한다. 다른 충돌 상태는 그대로 반환한다.
            Payment processing = paymentTransactionService.processing(request);
            if (processing == null) {
                throw exception;
            }

            return recover(processing, processor, request);
        }
        if (payment.getPaymentStatus() == PaymentStatus.SUCCESS) {
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

            paymentTransactionService.updateFailure(payment, processor, unknown, PaymentApiType.PAYMENT,
                    requestedAt, elapsedMillis(startedAt), exception);

            throw exception;
        }

        //PG 승인 실패와 결과 미확인 상태를 결제, 이력, API 로그에 함께 저장
        if (result.getStatus() != PaymentStatus.SUCCESS
                && result.getStatus() != PaymentStatus.PROCESSING) {
            paymentTransactionService.updateFailure(payment, processor, result, PaymentApiType.PAYMENT,
                    requestedAt, elapsedMillis(startedAt), null);

            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, result.getMessage());
        }

        //PG 응답을 받은 뒤 새 트랜잭션에서 최종 결제 상태 저장
        PaymentResponse paymentResponse = paymentTransactionService.updateResult(payment, processor,
                result, PaymentApiType.PAYMENT, requestedAt, elapsedMillis(startedAt));

        //결제 성공 커밋 후 이벤트 발행, 후속 업무의 비동기 실행은 수신 측에서 설정
        if (paymentResponse.getPaymentStatus() == PaymentStatus.SUCCESS) {
            publishCompleted(paymentResponse);
        }

        return paymentResponse;
    }

    /**
     * 재진입한 처리 중 결제의 실제 PG 상태를 조회해 저장된 결과를 확정한다.
     */
    private PaymentResponse recover(Payment payment, PaymentProcessor processor, PaymentRequest request) {
        LocalDateTime requestedAt = LocalDateTime.now();
        long startedAt = System.nanoTime();

        PaymentProcessResult result;
        try {
            result = Objects.requireNonNull(processor.lookup(payment, request));
            Objects.requireNonNull(result.getStatus(), "PG 조회 결과에 상태가 없습니다.");
        } catch (RuntimeException exception) {
            PaymentProcessResult unknown = PaymentProcessResult.builder()
                    .status(PaymentStatus.UNKNOWN)
                    .code("PG_LOOKUP_ERROR")
                    .message("PG 결제 결과 조회 중 예외가 발생했습니다.")
                    .build();

            paymentTransactionService.recordLookup(payment, processor, unknown,
                    requestedAt, elapsedMillis(startedAt), exception);
            throw exception;
        }

        long durationMs = elapsedMillis(startedAt);
        if (result.getStatus() == PaymentStatus.SUCCESS) {
            PaymentResponse response = paymentTransactionService.updateResult(payment, processor,
                    result, PaymentApiType.PAYMENT_LOOKUP, requestedAt, durationMs);

            publishCompleted(response);
            return response;
        }

        if (result.getStatus() == PaymentStatus.FAILED
                || result.getStatus() == PaymentStatus.CANCELLED
                || result.getStatus() == PaymentStatus.PARTIAL_CANCELLED) {
            paymentTransactionService.updateFailure(payment, processor, result,
                    PaymentApiType.PAYMENT_LOOKUP, requestedAt, durationMs, null);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, result.getMessage());
        }

        //조회가 아직 진행 중이거나 결과를 확정할 수 없으면 기존 PROCESSING 상태를 유지한다.
        paymentTransactionService.recordLookup(payment, processor, result,
                requestedAt, durationMs, null);
        if (result.getStatus() == PaymentStatus.PROCESSING) {
            return PaymentResponse.of(payment);
        }

        throw new ResponseStatusException(HttpStatus.CONFLICT, "PG 결제 결과를 아직 확인할 수 없습니다.");
    }

    /**
     * 결제 성공 결과가 커밋된 뒤 후속 업무 이벤트를 발행한다.
     */
    private void publishCompleted(PaymentResponse response) {
        try {
            eventPublisher.publishEvent(new PaymentCompletedEvent(
                    response.getPaymentId(), response.getProvider(),
                    response.getOrderId(), response.getAmount()));
        } catch (RuntimeException exception) {
            log.error("Payment completion event publication failed: paymentId={}",
                    response.getPaymentId(), exception);
        }
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
