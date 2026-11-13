package com.paymentModule.domain.payment.service;

import com.paymentModule.data.enums.PaymentApiType;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.domain.payment.dto.response.PaymentResponse;
import com.paymentModule.domain.payment.processor.PaymentProcessResult;
import com.paymentModule.domain.payment.processor.PaymentProcessor;
import com.paymentModule.event.payments.PaymentCompletedEvent;
import com.paymentModule.global.exception.PaymentException;
import com.paymentModule.global.properties.payments.PaymentRecoveryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
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
    private final PaymentRecoveryProperties recoveryProperties;

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

            //처리 중이거나 결과 미확인 상태인 시도만 PG 조회로 확인한다.
            Payment unresolved = paymentTransactionService.unresolvedPayment(request);
            if (unresolved == null) {
                throw exception;
            }

            return recover(unresolved, processor, request);
        }

        if (payment.getPaymentStatus() == PaymentStatus.SUCCESS) {
            return PaymentResponse.of(payment);
        }

        //PG 요청 시간과 응답 소요 시간을 API 로그에 남긴다.
        LocalDateTime requestedAt = LocalDateTime.now();
        long startedAt = System.nanoTime();

        //정상 PG 응답 이후의 DB 예외를 외부 통신 실패로 잘못 기록하지 않기 위한 구분값이다.
        boolean apiResultReceived = false;
        try {
            //PG API 오류는 PaymentException으로 받고, 정상 응답의 결제 상태는 아래에서 처리한다.
            PaymentProcessResult result = Objects.requireNonNull(processor.payment(payment, request));

            Objects.requireNonNull(result.getStatus(), "PG 결제 결과에 상태가 없습니다.");
            apiResultReceived = true;

            //HTTP 요청은 성공해도 결제 자체가 실패하거나 결과 미확인 상태일 수 있다.
            if (result.getStatus() != PaymentStatus.SUCCESS
                    && result.getStatus() != PaymentStatus.PROCESSING) {
                paymentTransactionService.writeResult(payment, processor, result, PaymentApiType.PAYMENT,
                        requestedAt, elapsedMillis(startedAt), null, true);

                throw new ResponseStatusException(HttpStatus.CONFLICT, result.getMessage());
            }

            //PG 응답을 받은 뒤 별도 트랜잭션에서 결과를 저장하고 성공한 결제만 이벤트를 발행한다.
            PaymentResponse response = paymentTransactionService.writeResult(payment, processor,
                    result, PaymentApiType.PAYMENT, requestedAt, elapsedMillis(startedAt), null, true);

            //입금 대기 중인 결제에는 완료 이벤트를 보내지 않는다.
            if (response.getPaymentStatus() == PaymentStatus.SUCCESS) {
                publishCompleted(response);
            }

            return response;
        } catch (PaymentException paymentException) {
            //PG 응답을 받은 뒤의 후속 작업에서 발생한 예외라면 PG 오류로 다시 저장하지 않는다.
            if (apiResultReceived) {
                throw paymentException;
            }

            //PG API 오류의 상태와 원래 오류 코드를 결제, 이력, 통신 로그에 기록한다.
            paymentTransactionService.writeResult(payment, processor, paymentException.getResult(),
                    PaymentApiType.PAYMENT, requestedAt, elapsedMillis(startedAt), paymentException, true);

            throw paymentException;
        } catch (Exception unexpectedException) {
            //상태 저장 과정의 예외는 이미 받은 PG 결과를 UNKNOWN으로 덮어쓸 근거가 아니다.
            if (apiResultReceived) {
                throw unexpectedException;
            }

            //그 밖의 호출 예외는 승인 여부를 알 수 없으므로 UNKNOWN으로 남긴다.
            PaymentProcessResult unknown = PaymentProcessResult.builder()
                    .status(PaymentStatus.UNKNOWN)
                    .code("PG_UNEXPECTED_ERROR")
                    .message("PG 승인 호출 중 예외가 발생했습니다.")
                    .build();

            paymentTransactionService.writeResult(payment, processor, unknown, PaymentApiType.PAYMENT,
                    requestedAt, elapsedMillis(startedAt), unexpectedException, true);

            throw new ResponseStatusException(HttpStatus.CONFLICT, "PG 승인 결과를 확인할 수 없습니다.", unexpectedException);
        }
    }

    /**
     * 처리 중이거나 결과 미확인 상태인 결제를 PG 조회로 확정한다.
     */
    private PaymentResponse recover(Payment payment, PaymentProcessor processor, PaymentRequest request) {
        //최초 승인에 사용한 결제 키로 PG를 다시 조회하고 통신 시간을 별도로 기록한다.
        LocalDateTime requestedAt = LocalDateTime.now();
        long startedAt = System.nanoTime();

        //조회 뒤 발생한 DB 예외를 PG 조회 예외와 구분한다.
        boolean apiResultReceived = false;
        try {
            PaymentProcessResult result = Objects.requireNonNull(processor.lookup(payment, request));
            Objects.requireNonNull(result.getStatus(), "PG 조회 결과에 상태가 없습니다.");

            apiResultReceived = true;

            long durationMs = elapsedMillis(startedAt);
            //조회 성공으로 승인을 확인한 경우에만 상태 확정 후 완료 이벤트를 발행한다.
            if (result.getStatus() == PaymentStatus.SUCCESS) {
                PaymentResponse response = paymentTransactionService.writeResult(payment, processor,
                        result, PaymentApiType.PAYMENT_LOOKUP, requestedAt, durationMs, null, true);

                publishCompleted(response);
                return response;
            }

            //PG의 정상 조회 응답 자체가 종료 상태라면 결제 상태와 이력도 확정한다.
            if (result.getStatus() == PaymentStatus.FAILED
                    || result.getStatus() == PaymentStatus.CANCELLED
                    || result.getStatus() == PaymentStatus.PARTIAL_CANCELLED) {
                paymentTransactionService.writeResult(payment, processor, result,
                        PaymentApiType.PAYMENT_LOOKUP, requestedAt, durationMs, null, true);

                throw new ResponseStatusException(HttpStatus.CONFLICT, result.getMessage());
            }

            //정상 조회 응답이 아직 진행 중이거나 불확실하면 결제 상태는 유지한다.
            paymentTransactionService.writeResult(payment, processor, result,
                    PaymentApiType.PAYMENT_LOOKUP, requestedAt, durationMs, null, false);
            //기존에도 처리 중이었다면 재진입 요청에 현재 상태를 그대로 반환한다.
            if (result.getStatus() == PaymentStatus.PROCESSING
                    && payment.getPaymentStatus() == PaymentStatus.PROCESSING) {
                return PaymentResponse.of(payment);
            }

            throw new ResponseStatusException(HttpStatus.CONFLICT, "PG 결제 결과를 아직 확인할 수 없습니다.");
        } catch (PaymentException pe) {
            //정상 조회 응답 이후의 후속 처리 예외는 다시 PG 오류로 기록하지 않는다.
            if (apiResultReceived) {
                throw pe;
            }

            PaymentProcessResult result = pe.getResult();
            //명시적인 결제 부재 404만 유예 시간 뒤 새 시도를 허용할 실패 후보로 본다.
            boolean notFound = result.getStatus() == PaymentStatus.FAILED
                    && result.getHttpStatus() != null && result.getHttpStatus() == 404;

            //최초 승인 요청이 아직 끝나지 않았을 수 있으므로 최근 404는 실패로 확정하지 않는다.
            boolean recentNotFound = notFound && recentAttempt(payment);
            //통신 오류와 유예 시간 안의 404는 API 로그만 남기고 결제 상태를 유지한다.
            boolean confirmedFailure = (result.getStatus() == PaymentStatus.FAILED
                    || result.getStatus() == PaymentStatus.CANCELLED
                    || result.getStatus() == PaymentStatus.PARTIAL_CANCELLED) && !recentNotFound;

            paymentTransactionService.writeResult(payment, processor, result,
                    PaymentApiType.PAYMENT_LOOKUP, requestedAt, elapsedMillis(startedAt), pe, confirmedFailure);

            if (recentNotFound) {
                throw new PaymentException(result, "결제 승인 결과를 확인하는 중입니다.");
            }
            if (notFound) {
                throw new PaymentException(result, "결제를 찾을 수 없습니다. 준비부터 다시 진행해 주세요.");
            }

            throw pe;
        } catch (Exception e) {
            //PG 조회가 끝난 뒤의 DB 예외는 조회 실패로 분류하지 않는다.
            if (apiResultReceived) {
                throw e;
            }

            //예상하지 못한 조회 예외는 결과를 알 수 없으므로 기존 결제 상태를 유지한다.
            PaymentProcessResult unknown = PaymentProcessResult.builder()
                    .status(PaymentStatus.UNKNOWN)
                    .code("PG_LOOKUP_ERROR")
                    .message("PG 결제 결과 조회 중 예외가 발생했습니다.")
                    .build();

            paymentTransactionService.writeResult(payment, processor, unknown,
                    PaymentApiType.PAYMENT_LOOKUP, requestedAt, elapsedMillis(startedAt), e, false);

            throw new ResponseStatusException(HttpStatus.CONFLICT, "PG 결제 결과를 확인할 수 없습니다.", e);
        }
    }

    /**
     * 최초 승인 요청이 아직 끝나지 않았을 수 있는 유예 시간인지 확인한다.
     */
    private boolean recentAttempt(Payment payment) {
        if (payment.getRequestedAt() == null) {
            return true;
        }

        return LocalDateTime.now().isBefore(payment.getRequestedAt()
                .plus(Duration.ofMillis(recoveryProperties.getNotFoundGraceMs())));
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
