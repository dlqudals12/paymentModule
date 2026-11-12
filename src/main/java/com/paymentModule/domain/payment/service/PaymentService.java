package com.paymentModule.domain.payment.service;

import com.paymentModule.data.dto.user.CustomUserDetail;
import com.paymentModule.data.enums.*;
import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.data.model.entity.payment.PaymentApiLog;
import com.paymentModule.data.model.entity.payment.PaymentHistory;
import com.paymentModule.data.repository.payment.PaymentApiLogRepository;
import com.paymentModule.data.repository.payment.PaymentHistoryRepository;
import com.paymentModule.data.repository.payment.PaymentRepository;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.domain.payment.dto.response.PaymentResponse;
import com.paymentModule.domain.payment.event.PaymentCompletedEvent;
import com.paymentModule.domain.payment.processor.PaymentProcessResult;
import com.paymentModule.domain.payment.processor.PaymentProcessor;
import com.paymentModule.global.util.AuthUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final PaymentApiLogRepository paymentApiLogRepository;

    private final TransactionTemplate transactionTemplate;

    private final Map<PaymentProvider, PaymentProcessor> paymentProcessors;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 결제 준비
     */
    @Transactional
    public PaymentResponse register(PaymentReadyRequest request) {
        CustomUserDetail user = AuthUtil.authentication();

        //PG별 주문번호 검증
        processor(request.getProvider()).validateReady(request);

        // TODO: MSA 연동 시 호출 서비스 인증과 주문 금액의 신뢰 경계를 확정한다.
        // 현재는 전달된 금액을 저장하며 결제 모듈에서 상품 가격을 계산하지 않는다.
        //동일 PG의 주문번호 중복 체크
        paymentRepository.findByProviderAndOrderId(request.getProvider(), request.getOrderId())
                .ifPresent(payment -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 준비된 주문입니다.");
                });

        //결제 준비 정보 저장
        Payment payment = paymentRepository.saveAndFlush(Payment.builder()
                .userId(user.getId())
                .loginId(user.getUserId())
                .orderId(request.getOrderId())
                .provider(request.getProvider())
                .paymentType(PaymentType.ONE_TIME)
                .status(PaymentStatus.READY)
                .amount(request.getAmount())
                .idempotencyKey(UUID.randomUUID().toString())
                .build());

        //결제 생성 이력 저장
        saveHistory(payment, PaymentHistoryType.CREATED, null, null, null);

        return PaymentResponse.of(payment);
    }

    /**
     * 결제 승인
     */
    public PaymentResponse payment(PaymentRequest request) {
        CustomUserDetail user = AuthUtil.authentication();

        //PG 처리기 선택 및 승인값 검증
        PaymentProcessor processor = processor(request.getProvider());

        //pg사 validation
        processor.validate(request);

        Payment payment = Objects.requireNonNull(transactionTemplate.execute(status -> {
            //결제 소유자와 처리 상태 확인
            Payment current = lockedPayment(request.getProvider(), request.getOrderId(), user.getId());

            //준비 단계에서 저장한 금액과 SDK 결과 비교
            if (!Objects.equals(current.getAmount(), request.getAmount())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "준비된 결제 금액과 다릅니다.");
            }

            if (current.getStatus() == PaymentStatus.SUCCESS) {
                return current;
            }

            if (current.getStatus() != PaymentStatus.READY) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "승인할 수 없는 결제 상태입니다.");
            }

            //중복 승인 방지를 위해 처리 중 상태를 먼저 저장
            current.process();
            saveHistory(current, PaymentHistoryType.PAYMENT_REQUESTED, PaymentStatus.READY, null, null);
            return current;
        }));

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
            // 외부 요청 이후 예외가 발생하면 승인 여부를 실패로 단정할 수 없다.
            PaymentProcessResult unknown = PaymentProcessResult.builder()
                    .status(PaymentStatus.UNKNOWN)
                    .code("PG_UNEXPECTED_ERROR")
                    .message("PG 승인 호출 중 예외가 발생했습니다.")
                    .build();

            updateFailure(payment, processor, unknown, requestedAt, elapsedMillis(startedAt), exception);

            throw exception;
        }

        //PG 승인 실패와 결과 미확인 상태를 결제, 이력, API 로그에 함께 저장
        if (result.getStatus() != PaymentStatus.SUCCESS
                && result.getStatus() != PaymentStatus.PROCESSING) {
            updateFailure(payment, processor, result, requestedAt, elapsedMillis(startedAt), null);

            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, result.getMessage());
        }

        //Pg 소요시간 계산
        long durationMs = elapsedMillis(startedAt);

        //PG 응답을 받은 뒤 새 트랜잭션에서 최종 결제 상태 저장
        PaymentResponse paymentResponse = Objects.requireNonNull(transactionTemplate.execute(status -> {
            //승인 요청 전 트랜잭션의 엔티티 대신 현재 결제 행을 잠그고 다시 조회
            Payment current = lockedPayment(payment.getProvider(), payment.getOrderId(), user.getId());

            //다른 요청이 결제 상태를 바꿨으면 승인 결과로 덮어쓰지 않음
            if (current.getStatus() != PaymentStatus.PROCESSING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "결제 상태가 변경되었습니다.");
            }

            //입금 대기 등 아직 완료되지 않은 결제는 처리 중 상태로 유지
            if (result.getStatus() == PaymentStatus.PROCESSING) {
                //입금 대기 결과 저장
                current.pending(result.getTransactionId(), result.getPaymentMethod());

                //결제 대기 상태 변경 이력 저장
                saveHistory(current, PaymentHistoryType.PAYMENT_PENDING,
                        PaymentStatus.PROCESSING, result.getCode(), result.getMessage());

                //PG 승인 요청과 대기 응답의 통신 기록 저장
                saveApiLog(current, processor, result, requestedAt, durationMs, null);

                return PaymentResponse.of(current);
            }

            //승인 성공 결과 저장
            current.approve(result.getTransactionId(), result.getPaymentMethod(), result.getApprovedAt());

            //결제 성공 상태 변경 이력 저장
            saveHistory(current, PaymentHistoryType.PAYMENT_SUCCEEDED,
                    PaymentStatus.PROCESSING, result.getCode(), result.getMessage());

            //PG 승인 요청과 성공 응답의 통신 기록 저장
            saveApiLog(current, processor, result, requestedAt, durationMs, null);

            return PaymentResponse.of(current);
        }));

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
     * 결제 소유자와 처리 상태 확인
     */
    private Payment lockedPayment(PaymentProvider provider, String orderId, Long userId) {
        Payment payment = paymentRepository.findByProviderAndOrderId(provider, orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

        if (!payment.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다.");
        }

        return payment;
    }

    /**
     * 결제 실패 상태 저장
     */
    private void updateFailure(Payment previous, PaymentProcessor processor, PaymentProcessResult result,
                               LocalDateTime requestedAt, long durationMs, RuntimeException exception) {
        transactionTemplate.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findByProviderAndOrderId(
                            previous.getProvider(), previous.getOrderId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

            payment.fail(result.getStatus(), limit(result.getCode(), 100), limit(result.getMessage(), 500));
            saveHistory(payment, historyType(result.getStatus()),
                    PaymentStatus.PROCESSING, result.getCode(), result.getMessage());
            saveApiLog(payment, processor, result, requestedAt, durationMs, exception);
        });
    }

    /**
     * 결제 상태 변경 이력 저장
     */
    private void saveHistory(Payment payment, PaymentHistoryType type,
                             PaymentStatus previousStatus, String code, String message) {
        paymentHistoryRepository.save(PaymentHistory.builder()
                .paymentId(payment.getId())
                .userId(payment.getUserId())
                .loginId(payment.getLoginId())
                .historyType(type)
                .previousStatus(previousStatus)
                .currentStatus(payment.getStatus())
                .transactionAmount(type == PaymentHistoryType.PAYMENT_SUCCEEDED ? payment.getAmount() : null)
                .cumulativeCancelledAmount(payment.getCancelledAmount())
                .providerTransactionId(payment.getTransactionId())
                .resultCode(limit(code, 100))
                .resultMessage(limit(message, 500))
                .build());
    }

    /**
     * PG API 호출 결과 저장
     */
    private void saveApiLog(Payment payment, PaymentProcessor processor, PaymentProcessResult result,
                            LocalDateTime requestedAt, long durationMs, RuntimeException exception) {
        //인증 헤더와 결제 본문에는 민감정보가 있으므로 로그 테이블에 저장하지 않는다.
        paymentApiLogRepository.save(PaymentApiLog.builder()
                .paymentId(payment.getId())
                .userId(payment.getUserId())
                .loginId(payment.getLoginId())
                .transactionId(result.getTransactionId())
                .provider(payment.getProvider())
                .apiType(PaymentApiType.PAYMENT)
                .httpMethod(processor.requestMethod().name())
                .requestUrl(processor.paymentUrl())
                .responseStatus(result.getHttpStatus())
                .successYn(result.getStatus() == PaymentStatus.SUCCESS
                        || result.getStatus() == PaymentStatus.PROCESSING)
                .durationMs(durationMs)
                .errorType(exception == null ? limit(result.getCode(), 100)
                        : exception.getClass().getSimpleName())
                .errorMessage(limit(result.getMessage(), 1000))
                .requestedAt(requestedAt)
                .respondedAt(result.getHttpStatus() == null ? null : LocalDateTime.now())
                .build());
    }

    /**
     * 결과 상태에 맞는 이력 유형 선택
     */
    private PaymentHistoryType historyType(PaymentStatus status) {
        return switch (status) {
            case UNKNOWN -> PaymentHistoryType.PAYMENT_UNKNOWN;
            case CANCELLED, PARTIAL_CANCELLED -> PaymentHistoryType.PAYMENT_CANCELED;
            default -> PaymentHistoryType.PAYMENT_FAILED;
        };
    }

    /**
     * 컬럼 길이를 넘는 외부 오류 문구 자르기
     */
    private String limit(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    /**
     * PG 요청 소요 시간 계산
     */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

}
