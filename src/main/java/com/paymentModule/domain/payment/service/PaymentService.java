package com.paymentModule.domain.payment.service;

import com.paymentModule.data.enums.PaymentApiType;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.data.repository.payment.PaymentRepository;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.domain.payment.dto.response.PaymentResponse;
import com.paymentModule.domain.payment.processor.PaymentProcessResult;
import com.paymentModule.domain.payment.processor.PaymentProcessor;
import com.paymentModule.domain.payment.processor.PaymentProcessorData;
import com.paymentModule.domain.payment.processor.PaymentRegistry;
import com.paymentModule.event.payments.PaymentCompletedEvent;
import com.paymentModule.global.exception.PaymentException;
import com.paymentModule.global.properties.payments.PaymentRecoveryProperties;
import com.paymentModule.global.util.AuthUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentTransactionService paymentTransactionService;
    private final PaymentRegistry paymentRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentRecoveryProperties recoveryProperties;
    private final PaymentRepository paymentRepository;

    /**
     * 로그인한 사용자의 저장된 결제를 최신순으로 조회한다.
     */
    @Transactional(readOnly = true)
    public Page<PaymentResponse> payments(int page) {
        Long userId = AuthUtil.authentication().getId();
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, 20))
                .map(PaymentResponse::of);
    }

    /**
     * 로그인한 사용자의 결제 한 건을 조회한다.
     */
    @Transactional(readOnly = true)
    public PaymentResponse payment(Long paymentId) {
        Long userId = AuthUtil.authentication().getId();
        Payment payment = paymentRepository.findByIdAndUserId(paymentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

        return PaymentResponse.of(payment);
    }

    /**
     * 결제 준비
     */
    public PaymentResponse register(PaymentReadyRequest request) {
        //PG별 주문번호 검증
        paymentRegistry.processor(request.getProvider()).orderIdValidation(request);

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
     * 기존 결제 ID로 재시도를 시작한다. 결과 미확인 상태는 PG 조회로 먼저 확정한다.
     */
    public PaymentResponse rePayment(Long paymentId) {
        Long userId = AuthUtil.authentication().getId();
        Payment current = paymentRepository.findByIdAndUserId(paymentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

        //이미 성공한 결제에는 새 PG 승인을 보내지 않는다.
        if (current.getPaymentStatus() == PaymentStatus.SUCCESS) {
            return PaymentResponse.of(current);
        }

        if (current.getPaymentStatus() == PaymentStatus.UNKNOWN) {
            //결제 결과가 불확실할 때 새 SDK 시도를 열기 전에 저장된 PG 키로 즉시 조회한다.
            if (current.getExternalPaymentId() == null || current.getExternalPaymentId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "조회할 PG 결제 키가 없습니다.");
            }

            try {
                PaymentResponse resolved = recover(current, paymentRegistry.processor(current.getProvider()),
                        current.getExternalPaymentId());

                if (resolved.getPaymentStatus() == PaymentStatus.SUCCESS) {
                    return resolved;
                }

                throw new ResponseStatusException(HttpStatus.CONFLICT, "기존 결제 결과를 아직 확인할 수 없습니다.");
            } catch (PaymentException | ResponseStatusException exception) {
                //별도 조회 없이 잠금 트랜잭션에서 확정된 최신 상태를 확인한다.
                return paymentTransactionService.rePayment(paymentId);
            }
        }

        //SDK 인증은 이 응답 이후 브라우저에서 수행하고, 새 키의 승인은 기존 payment API로 처리한다.
        return paymentTransactionService.rePayment(paymentId);
    }

    /**
     * 결제 승인
     */
    public PaymentResponse payment(PaymentRequest request) {
        //PG 처리기 선택 및 승인값 검증
        PaymentProcessor processor = paymentRegistry.processor(request.getProvider());
        processor.paymentValidation(request);

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

            return recover(unresolved, processor, request.getExternalPaymentId());
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
            PaymentProcessResult result = Objects.requireNonNull(processor.payment(processorData(payment), request));

            Objects.requireNonNull(result.getStatus(), "PG 결제 결과에 상태가 없습니다.");
            apiResultReceived = true;

            //HTTP 요청은 성공해도 결제 자체가 실패하거나 결과 미확인 상태일 수 있다.
            if (result.getStatus() != PaymentStatus.SUCCESS
                    && result.getStatus() != PaymentStatus.PROCESSING
                    && result.getStatus() != PaymentStatus.WAITING_FOR_PAYMENT) {
                paymentTransactionService.writeResult(payment, result, PaymentApiType.PAYMENT,
                        requestedAt, elapsedMillis(startedAt), null, true);

                throw new ResponseStatusException(HttpStatus.CONFLICT, result.getMessage());
            }

            //PG 응답을 받은 뒤 별도 트랜잭션에서 결과를 저장하고 성공한 결제만 이벤트를 발행한다.
            PaymentResponse response = paymentTransactionService.writeResult(payment,
                    result, PaymentApiType.PAYMENT, requestedAt, elapsedMillis(startedAt), null, true);

            //PG가 완료를 기다리는 결제에는 완료 이벤트를 보내지 않는다.
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
            paymentTransactionService.writeResult(payment, paymentException.getResult(),
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

            paymentTransactionService.writeResult(payment, unknown, PaymentApiType.PAYMENT,
                    requestedAt, elapsedMillis(startedAt), unexpectedException, true);

            throw new ResponseStatusException(HttpStatus.CONFLICT, "PG 승인 결과를 확인할 수 없습니다.", unexpectedException);
        }
    }

    /**
     * 스케줄러에서 저장된 결제 키로 미확정 결제의 PG 결과를 확인한다.
     */
    public PaymentResponse recover(Payment payment) {
        return recover(payment, paymentRegistry.processor(payment.getProvider()), payment.getExternalPaymentId());
    }

    /**
     * 사용자 재요청과 스케줄러가 같은 PG 조회 및 결과 저장 흐름을 사용한다.
     */
    private PaymentResponse recover(Payment payment, PaymentProcessor processor, String externalPaymentId) {
        return recover(payment, processor, externalPaymentId, recoveryProperties.getNotFoundRetryCount());
    }

    /**
     * 결제 부재 404만 제한된 횟수로 다시 조회하고, 각 PG 호출은 별도 로그로 남긴다.
     */
    private PaymentResponse recover(Payment payment, PaymentProcessor processor,
                                    String externalPaymentId, int remainingRetries) {
        //최초 승인에 사용한 결제 키로 PG를 다시 조회하고 통신 시간을 별도로 기록한다.
        LocalDateTime requestedAt = LocalDateTime.now();
        long startedAt = System.nanoTime();

        //조회 뒤 발생한 DB 예외를 PG 조회 예외와 구분한다.
        boolean apiResultReceived = false;
        try {
            PaymentProcessResult result = Objects.requireNonNull(processor.lookup(processorData(payment), externalPaymentId));

            Objects.requireNonNull(result.getStatus(), "PG 조회 결과에 상태가 없습니다.");

            apiResultReceived = true;

            long durationMs = elapsedMillis(startedAt);
            //조회 성공으로 승인을 확인한 경우에만 상태 확정 후 완료 이벤트를 발행한다.
            if (result.getStatus() == PaymentStatus.SUCCESS) {
                PaymentResponse response = paymentTransactionService.writeResult(payment,
                        result, PaymentApiType.PAYMENT_LOOKUP, requestedAt, durationMs, null, true);

                publishCompleted(response);
                return response;
            }

            //PG의 정상 조회 응답 자체가 종료 상태라면 결제 상태와 이력도 확정한다.
            if (result.getStatus() == PaymentStatus.FAILED
                    || result.getStatus() == PaymentStatus.CANCELLED
                    || result.getStatus() == PaymentStatus.PARTIAL_CANCELLED) {
                paymentTransactionService.writeResult(payment, result,
                        PaymentApiType.PAYMENT_LOOKUP, requestedAt, durationMs, null, true);

                throw new ResponseStatusException(HttpStatus.CONFLICT, result.getMessage());
            }

            //PG가 정상 대기 상태를 확인했다면 기존의 결과 미확인 상태도 대기로 확정한다.
            if (result.getStatus() == PaymentStatus.PROCESSING
                    || result.getStatus() == PaymentStatus.WAITING_FOR_PAYMENT) {
                boolean statusChanged = payment.getPaymentStatus() != result.getStatus()
                        && payment.getPaymentStatus() != PaymentStatus.WAITING_FOR_PAYMENT;

                return paymentTransactionService.writeResult(payment, result,
                        PaymentApiType.PAYMENT_LOOKUP, requestedAt, durationMs, null, statusChanged);
            }

            //조회 결과가 불확실하면 실제 PG 통신만 기록하고 기존 결제 상태를 유지한다.
            paymentTransactionService.writeResult(payment, result,
                    PaymentApiType.PAYMENT_LOOKUP, requestedAt, durationMs, null, false);

            throw new ResponseStatusException(HttpStatus.CONFLICT, "PG 결제 결과를 아직 확인할 수 없습니다.");
        } catch (PaymentException pe) {
            //정상 조회 응답 이후의 후속 처리 예외는 다시 PG 오류로 기록하지 않는다.
            if (apiResultReceived) {
                throw pe;
            }

            PaymentProcessResult result = pe.getResult();
            //명시적인 결제 부재 404만 재조회하고, 다른 조회 오류는 기존 상태를 유지한다.
            boolean notFound = result.getStatus() == PaymentStatus.FAILED
                    && result.getHttpStatus() != null && result.getHttpStatus() == 404;

            if (notFound && remainingRetries > 0) {
                //이번 404는 기록하되 실패로 확정하지 않고, 정해진 간격 뒤 같은 결제 키를 재조회한다.
                paymentTransactionService.writeResult(payment, result,
                        PaymentApiType.PAYMENT_LOOKUP, requestedAt, elapsedMillis(startedAt), pe, false);

                //결제 재시도 전 대기
                waitBeforeNotFoundRetry();

                return recover(payment, processor, externalPaymentId, remainingRetries - 1);
            }

            //재조회까지 모두 결제 부재이거나 PG가 확정 실패를 알린 경우에만 상태를 변경한다.
            boolean confirmedFailure = (result.getStatus() == PaymentStatus.FAILED
                    || result.getStatus() == PaymentStatus.CANCELLED
                    || result.getStatus() == PaymentStatus.PARTIAL_CANCELLED);

            paymentTransactionService.writeResult(payment, result,
                    PaymentApiType.PAYMENT_LOOKUP, requestedAt, elapsedMillis(startedAt), pe, confirmedFailure);

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

            paymentTransactionService.writeResult(payment, unknown,
                    PaymentApiType.PAYMENT_LOOKUP, requestedAt, elapsedMillis(startedAt), e, false);

            throw new ResponseStatusException(HttpStatus.CONFLICT, "PG 결제 결과를 확인할 수 없습니다.", e);
        }
    }

    /**
     * 404 재조회 전 지정된 간격을 기다린다. 중단되면 결제 상태를 유지한다.
     */
    private void waitBeforeNotFoundRetry() {
        try {
            TimeUnit.MILLISECONDS.sleep(recoveryProperties.getNotFoundRetryDelayMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "결제 조회 재시도가 중단되었습니다.", exception);
        }
    }

    /**
     * 결제 성공 결과가 커밋된 뒤 후속 업무 이벤트를 발행한다.
     */
    private void publishCompleted(PaymentResponse response) {
        try {
            eventPublisher.publishEvent(new PaymentCompletedEvent(
                    response.getId(), response.getProvider(),
                    response.getOrderId(), response.getAmount()));
        } catch (RuntimeException exception) {
            log.error("Payment completion event publication failed: paymentId={}",
                    response.getId(), exception);
        }
    }

    /**
     * PG 구현에 JPA 엔티티를 넘기지 않고 결제 행의 현재 값을 복사한다.
     */
    private PaymentProcessorData processorData(Payment payment) {
        //연관 User 엔티티는 전달하지 않고 결제 행에 저장된 사용자 식별값만 복사한다.
        return PaymentProcessorData.builder()
                .id(payment.getId())
                .userId(payment.getUserId())
                .loginId(payment.getLoginId())
                .orderId(payment.getOrderId())
                .activeOrderId(payment.getActiveOrderId())
                .externalPaymentId(payment.getExternalPaymentId())
                .provider(payment.getProvider())
                .paymentType(payment.getPaymentType())
                .paymentMethod(payment.getPaymentMethod())
                .paymentStatus(payment.getPaymentStatus())
                .paymentStatusChangedAt(payment.getPaymentStatusChangedAt())
                .orderStatus(payment.getOrderStatus())
                .orderStatusChangedAt(payment.getOrderStatusChangedAt())
                .amount(payment.getAmount())
                .cancelledAmount(payment.getCancelledAmount())
                .idempotencyKey(payment.getIdempotencyKey())
                .failureCode(payment.getFailureCode())
                .failureMessage(payment.getFailureMessage())
                .requestedAt(payment.getRequestedAt())
                .approvedAt(payment.getApprovedAt())
                .cancelledAt(payment.getCancelledAt())
                .createdBy(payment.getCreatedBy())
                .createdAt(payment.getCreatedAt())
                .updatedBy(payment.getUpdatedBy())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }

    /**
     * PG 요청 소요 시간 계산
     */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
