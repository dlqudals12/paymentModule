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
import com.paymentModule.global.exception.PaymentException;
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
    private final PaymentAmountCalculator paymentAmountCalculator;

    /**
     * 결제 준비 정보와 생성 이력 저장
     */
    @Transactional
    public PaymentResponse register(PaymentReadyRequest request) {
        CustomUserDetail user = AuthUtil.authentication();

        //SDK를 열기 전에 주문 식별자로 서버 금액을 확인한다.
        Long amount = paymentAmountCalculator.calculate(request.getOrderId());

        //실패한 결제는 조회 대상에서 제외하고, 진행 가능한 같은 주문만 확인한다.
        return paymentRepository.findByProviderAndActiveOrderId(
                        request.getProvider(), request.getOrderId())
                .map(current -> updateReady(current, user, amount))
                .orElseGet(() -> registerReady(request, user, amount));
    }

    /**
     * 같은 주문의 준비된 결제 시도를 갱신한다.
     */
    private PaymentResponse updateReady(Payment current, CustomUserDetail user, Long amount) {
        if (!current.getUserId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다.");
        }

        if (current.getPaymentStatus() != PaymentStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다시 준비할 수 없는 결제 상태입니다.");
        }

        //새로고침으로 다시 준비하면 같은 시도를 유지하고 서버 금액으로 갱신한다.
        current.updateReadyAmount(amount);
        return PaymentResponse.of(current);
    }

    /**
     * 진행 가능한 결제 시도가 없으면 새로 저장한다.
     */
    private PaymentResponse registerReady(PaymentReadyRequest request, CustomUserDetail user, Long amount) {
        //결제 준비 정보 저장
        Payment payment = paymentRepository.save(Payment.builder()
                .userId(user.getId())
                .loginId(user.getUserId())
                .orderId(request.getOrderId())
                .activeOrderId(request.getOrderId())
                .provider(request.getProvider())
                .paymentType(PaymentType.ONE_TIME)
                .paymentStatus(PaymentStatus.READY)
                .amount(amount)
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
        Payment current = verifiedPayment(request, user.getId());

        if (current.getPaymentStatus() == PaymentStatus.SUCCESS) {
            return current;
        }

        if (current.getPaymentStatus() != PaymentStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "승인할 수 없는 결제 상태입니다.");
        }

        //중복 승인 방지를 위해 처리 중 상태를 먼저 저장
        current.process(request.getPaymentKey());
        saveHistory(current, PaymentHistoryType.PAYMENT_REQUESTED, PaymentStatus.READY, null, null);

        return current;
    }

    /**
     * 재진입한 요청이 처리 중이거나 결과 미확인 상태인지 확인한다.
     */
    @Transactional
    public Payment unresolvedPayment(PaymentRequest request) {
        CustomUserDetail user = AuthUtil.authentication();
        Payment current = verifiedPayment(request, user.getId());

        boolean unresolved = current.getPaymentStatus() == PaymentStatus.PROCESSING
                || current.getPaymentStatus() == PaymentStatus.UNKNOWN;

        //미확정 시도는 최초 승인에 사용한 결제 키로만 복구할 수 있다.
        if (unresolved
                && !Objects.equals(current.getTransactionId(), request.getPaymentKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 결제 키로 진행 중인 시도입니다.");
        }

        return unresolved ? current : null;
    }

    /**
     * PG 승인·조회 결과를 결제 상태, 이력, 통신 로그에 저장한다.
     */
    @Transactional
    public PaymentResponse writeResult(Payment previous, PaymentProcessor processor, PaymentProcessResult result,
                                       PaymentApiType apiType, LocalDateTime requestedAt,
                                       long durationMs, Exception exception, boolean updateStatus) {
        CustomUserDetail user = AuthUtil.authentication();

        //PG 호출 전 엔티티 대신 현재 결제 행을 잠그고 다시 조회한다.
        Payment current = payment(previous.getProvider(), previous.getOrderId(), user.getId());

        if (updateStatus && current.getPaymentStatus() == PaymentStatus.SUCCESS
                && result.getStatus() == PaymentStatus.SUCCESS
                && Objects.equals(current.getTransactionId(), result.getTransactionId())) {
            //다른 요청이 같은 결제 키의 성공을 먼저 확정했다면 이력은 중복 생성하지 않는다.
            saveApiLog(current, processor, result, apiType, requestedAt, durationMs, exception);
            return PaymentResponse.of(current);
        }

        if (updateStatus) {
            PaymentStatus previousStatus = current.getPaymentStatus();
            if (previousStatus != PaymentStatus.PROCESSING && previousStatus != PaymentStatus.UNKNOWN) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "결제 상태가 변경되었습니다.");
            }

            //정상 승인, 입금 대기, 실패 확정은 각 상태에 맞게 결제와 이력을 함께 변경한다.
            switch (result.getStatus()) {
                case SUCCESS -> {
                    current.approve(result.getTransactionId(), result.getPaymentMethod(), result.getApprovedAt());
                    saveHistory(current, PaymentHistoryType.PAYMENT_SUCCEEDED,
                            previousStatus, result.getCode(), result.getMessage());
                }
                case PROCESSING -> {
                    current.pending(result.getTransactionId(), result.getPaymentMethod());
                    saveHistory(current, PaymentHistoryType.PAYMENT_PENDING,
                            previousStatus, result.getCode(), result.getMessage());
                }
                case FAILED, UNKNOWN, PARTIAL_CANCELLED, CANCELLED -> {
                    current.fail(result.getStatus(), limit(result.getCode(), 100), limit(result.getMessage(), 500));
                    saveHistory(current, result.getStatus().getFailureHistoryType(),
                            previousStatus, result.getCode(), result.getMessage());
                }
                default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "저장할 수 없는 결제 상태입니다.");
            }
        }

        //조회 결과가 불확실한 경우에도 실제 PG 통신 기록은 남긴다.
        saveApiLog(current, processor, result, apiType, requestedAt, durationMs, exception);
        return PaymentResponse.of(current);
    }

    /**
     * 결제 완료 이벤트를 받으면 주문 반영을 시작하고 이미 완료된 이벤트는 건너뛴다.
     */
    @Transactional
    public boolean updateOrderPending(Long paymentId) {
        Payment payment = paymentRepository.findLockedById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

        if (payment.getPaymentStatus() != PaymentStatus.SUCCESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "주문을 반영할 수 없는 결제 상태입니다.");
        }

        if (payment.getOrderStatus() == OrderStatus.COMPLETE) {
            return false;
        }

        if (payment.getOrderStatus() == OrderStatus.BEFORE) {
            payment.beginOrder();
        }

        return true;
    }

    /**
     * 주문 반영 작업이 실제로 성공한 뒤 상태를 완료로 기록한다.
     */
    @Transactional
    public void updateOrderComplete(Long paymentId) {
        Payment payment = paymentRepository.findLockedById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

        if (payment.getOrderStatus() == OrderStatus.COMPLETE) {
            return;
        }

        if (payment.getPaymentStatus() != PaymentStatus.SUCCESS
                || payment.getOrderStatus() != OrderStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "주문 반영을 완료할 수 없는 결제 상태입니다.");
        }

        payment.completeOrder();
    }

    /**
     * 결제 소유자와 처리 상태 확인
     */
    private Payment payment(PaymentProvider provider, String orderId, Long userId) {
        //같은 PG와 주문의 진행 가능한 결제 행을 잠가 중복 승인 요청을 직렬화한다.
        Payment payment = paymentRepository.findByProviderAndActiveOrderId(provider, orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

        //타인의 주문번호로 결제 존재 여부를 알아내지 못하도록 동일한 404를 반환한다.
        if (!payment.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다.");
        }

        return payment;
    }

    /**
     * 저장 금액과 현재 서버 금액, SDK 금액이 모두 같은 결제만 반환한다.
     */
    private Payment verifiedPayment(PaymentRequest request, Long userId) {
        Payment current = payment(request.getProvider(), request.getOrderId(), userId);

        //복구 진입에서도 최초 승인과 동일한 금액 검증을 수행한다.
        Long amount = paymentAmountCalculator.calculate(request.getOrderId());
        if (!Objects.equals(current.getAmount(), amount)
                || !Objects.equals(amount, request.getAmount())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "준비된 결제 금액과 다릅니다.");
        }

        return current;
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
                .currentStatus(payment.getPaymentStatus())
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
                            PaymentApiType apiType, LocalDateTime requestedAt,
                            long durationMs, Exception exception) {
        //인증 헤더와 결제 본문에는 민감정보가 있으므로 로그 테이블에 저장하지 않는다.
        paymentApiLogRepository.save(PaymentApiLog.builder()
                .paymentId(payment.getId())
                .userId(payment.getUserId())
                .loginId(payment.getLoginId())
                .transactionId(result.getTransactionId() == null
                        ? payment.getTransactionId() : result.getTransactionId())
                .provider(payment.getProvider())
                .apiType(apiType)
                .httpMethod(apiType == PaymentApiType.PAYMENT_LOOKUP ? "GET" : processor.requestMethod().name())
                .requestUrl(apiType == PaymentApiType.PAYMENT_LOOKUP
                        ? processor.lookupUrl() : processor.paymentUrl())
                .responseStatus(result.getHttpStatus())
                .successYn(result.getHttpStatus() != null && result.getHttpStatus() >= 200
                        && result.getHttpStatus() < 300
                        && (result.getStatus() == PaymentStatus.SUCCESS
                        || result.getStatus() == PaymentStatus.PROCESSING))
                .durationMs(durationMs)
                //PG가 제공한 오류 코드는 보존하고, 그 밖의 예외는 실제 예외 유형을 기록한다.
                .errorType(exception == null || exception instanceof PaymentException
                        ? limit(result.getCode(), 100) : exception.getClass().getSimpleName())
                .errorMessage(limit(result.getMessage(), 1000))
                .requestedAt(requestedAt)
                .respondedAt(result.getHttpStatus() == null ? null : LocalDateTime.now())
                .build());
    }

    /**
     * 컬럼 길이를 넘는 외부 오류 문구 자르기
     */
    private String limit(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
