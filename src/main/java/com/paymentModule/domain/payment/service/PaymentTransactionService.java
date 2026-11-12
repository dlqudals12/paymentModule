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
import com.paymentModule.domain.payment.processor.PaymentProcessResult;
import com.paymentModule.domain.payment.processor.PaymentProcessor;
import com.paymentModule.global.util.AuthUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final PaymentApiLogRepository paymentApiLogRepository;

    /**
     * 결제 준비 정보와 생성 이력 저장
     */
    @Transactional
    public PaymentResponse register(PaymentReadyRequest request) {
        CustomUserDetail user = AuthUtil.authentication();

        //실패한 결제는 조회 대상에서 제외하고, 진행 가능한 같은 주문만 확인한다.
        Payment current = paymentRepository.findByProviderAndActiveOrderId(
                request.getProvider(), request.getOrderId()).orElse(null);

        if (current != null) {
            if (!current.getUserId().equals(user.getId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다.");
            }

            if (current.getStatus() != PaymentStatus.READY) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "다시 준비할 수 없는 결제 상태입니다.");
            }

            //새로고침으로 다시 준비하면 같은 시도를 유지하고 금액만 갱신한다.
            // TODO: 요청 금액 대신 orderId로 조회한 주문의 서버 확정 금액을 사용한다.
            current.updateReadyAmount(request.getAmount());
            return PaymentResponse.of(current);
        }

        //결제 준비 정보 저장
        Payment payment = paymentRepository.save(Payment.builder()
                .userId(user.getId())
                .loginId(user.getUserId())
                .orderId(request.getOrderId())
                .activeOrderId(request.getOrderId())
                .provider(request.getProvider())
                .paymentType(PaymentType.ONE_TIME)
                .status(PaymentStatus.READY)
                // TODO: orderId로 주문을 조회하고 서버에서 확정한 결제 금액으로 대체한다. 요청 금액은 신뢰하지 않는다.
                .amount(request.getAmount())
                .idempotencyKey(UUID.randomUUID().toString())
                .build());

        //결제 생성 이력 저장
        saveHistory(payment, PaymentHistoryType.CREATED, null, null, null);

        return PaymentResponse.of(payment);
    }

    /**
     * 결제 승인 전 상태 저장
     */
    @Transactional
    public Payment updateProcessing(PaymentRequest request) {
        CustomUserDetail user = AuthUtil.authentication();

        //결제 소유자와 처리 상태 확인
        Payment current = lockedPayment(request.getProvider(), request.getOrderId(), user.getId());

        // TODO: orderId로 주문 금액을 다시 계산해 준비 금액과도 대조한다.
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
    }

    /**
     * PG 승인 결과와 이력, 통신 로그 저장
     */
    @Transactional
    public PaymentResponse updateResult(Payment previous, PaymentProcessor processor,
                                        PaymentProcessResult result, LocalDateTime requestedAt, long durationMs) {
        CustomUserDetail user = AuthUtil.authentication();

        //승인 요청 전 트랜잭션의 엔티티 대신 현재 결제 행을 잠그고 다시 조회
        Payment current = lockedPayment(previous.getProvider(), previous.getOrderId(), user.getId());

        //다른 요청이 결제 상태를 바꿨으면 승인 결과로 덮어쓰지 않음
        if (current.getStatus() != PaymentStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "결제 상태가 변경되었습니다.");
        }

        //입금 대기 등 아직 완료되지 않은 결제는 처리 중 상태로 유지
        if (result.getStatus() == PaymentStatus.PROCESSING) {
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
    }

    /**
     * PG 실패 또는 결과 미확인 상태와 통신 로그 저장
     */
    @Transactional
    public void updateFailure(Payment previous, PaymentProcessor processor, PaymentProcessResult result,
                              LocalDateTime requestedAt, long durationMs, RuntimeException exception) {
        CustomUserDetail user = AuthUtil.authentication();

        Payment payment = lockedPayment(previous.getProvider(), previous.getOrderId(), user.getId());

        payment.fail(result.getStatus(), limit(result.getCode(), 100), limit(result.getMessage(), 500));

        saveHistory(payment, historyType(result.getStatus()),
                PaymentStatus.PROCESSING, result.getCode(), result.getMessage());

        saveApiLog(payment, processor, result, requestedAt, durationMs, exception);
    }

    /**
     * 결제 소유자와 처리 상태 확인
     */
    private Payment lockedPayment(PaymentProvider provider, String orderId, Long userId) {
        Payment payment = paymentRepository.findByProviderAndActiveOrderId(provider, orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

        if (!payment.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다.");
        }

        return payment;
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
}
