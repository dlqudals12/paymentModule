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

        //주문당 현재 결제 한 행을 유지한다. 실패한 행도 잠가 재준비하고 미확정 상태는 덮어쓰지 않는다.
        return paymentRepository.findByOrderId(request.getOrderId())
                .map(current -> updateReady(current, request, user, amount))
                .orElseGet(() -> registerReady(request, user, amount));
    }

    /**
     * 실패가 확정된 결제 행을 잠그고 서버 금액으로 새 SDK 시도를 준비한다.
     */
    @Transactional
    public PaymentResponse rePayment(Long paymentId) {
        CustomUserDetail user = AuthUtil.authentication();
        Payment current = paymentRepository.findLockedById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

        //결제 ID를 알아도 타인의 결제를 재준비할 수 없도록 소유자를 다시 확인한다.
        if (!current.getUserId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다.");
        }

        if (current.getPaymentStatus() == PaymentStatus.SUCCESS) {
            //PG 조회와 재준비 사이에 다른 요청이 성공을 확정했으면 기존 결과를 반환한다.
            return PaymentResponse.of(current);
        }

        if (current.getPaymentStatus() == PaymentStatus.READY) {
            //중복 재시도 요청은 서버 금액만 다시 확인하고 멱등키는 유지한다.
            current.updateReadyAmount(paymentAmountCalculator.calculate(current.getOrderId()));
            return PaymentResponse.of(current);
        }

        if (current.getPaymentStatus() != PaymentStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "재결제할 수 없는 결제 상태입니다.");
        }

        Long amount = paymentAmountCalculator.calculate(current.getOrderId());

        return retryReady(current, user, amount, current.getProvider());
    }

    /**
     * 같은 주문의 준비 상태는 갱신하고 실패 상태는 같은 행에서 재준비한다.
     */
    private PaymentResponse updateReady(Payment current, PaymentReadyRequest request,
                                        CustomUserDetail user, Long amount) {
        //다른 사용자가 같은 주문번호로 만든 결제라면 존재 여부를 드러내지 않는다.
        if (!current.getUserId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다.");
        }

        if (current.getPaymentStatus() == PaymentStatus.FAILED) {
            //기존 실패 결과는 이력·API 로그에 남기고 현재 행만 새 시도로 초기화한다.
            return retryReady(current, user, amount, request.getProvider());
        }

        //이미 SDK를 시작한 시도는 덮어쓰지 않는다. READY의 PG사 변경도 새 시도로 간주한다.
        if (current.getPaymentStatus() != PaymentStatus.READY
                || current.getProvider() != request.getProvider()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다시 준비할 수 없는 결제 상태입니다.");
        }

        //새로고침으로 다시 준비하면 같은 시도를 유지하고 서버 금액으로 갱신한다.
        current.updateReadyAmount(amount);

        return PaymentResponse.of(current);
    }

    /**
     * 기존 실패 시도를 보존할 이력을 남기고 현재 결제 행만 새 준비 상태로 바꾼다.
     */
    private PaymentResponse retryReady(Payment current, CustomUserDetail user, Long amount,
                                       PaymentProvider provider) {
        current.retryReady(provider, user.getUserId(), amount);
        saveHistory(current, PaymentHistoryType.READY_RETRIED, PaymentStatus.FAILED, null, null, null);

        return PaymentResponse.of(current);
    }

    /**
     * 주문의 현재 결제 행이 없으면 처음 준비 정보를 저장한다.
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
        saveHistory(payment, payment.getPaymentStatus().getPaymentHistoryType(), null, null, null, null);

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

        //이미 성공한 요청은 PG에 다시 승인하지 않고 저장된 결제를 반환한다.
        if (current.getPaymentStatus() == PaymentStatus.SUCCESS) {
            return current;
        }

        //READY 이외의 상태는 중복 승인을 막고, 미확정 상태는 호출부의 복구 경로로 보낸다.
        if (current.getPaymentStatus() != PaymentStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "승인할 수 없는 결제 상태입니다.");
        }

        //중복 승인 방지를 위해 처리 중 상태를 먼저 저장
        current.process(request.getExternalPaymentId());

        //PROCESSING 진입은 PG 응답 대기와 달리 승인 요청을 시작한 이력으로 기록한다.
        saveHistory(current, PaymentHistoryType.PAYMENT_REQUESTED, PaymentStatus.READY, null, null, null);

        return current;
    }

    /**
     * 재진입한 요청이 처리 중이거나 결과 미확인 상태인지 확인한다.
     */
    @Transactional
    public Payment unresolvedPayment(PaymentRequest request) {
        CustomUserDetail user = AuthUtil.authentication();

        //재진입에서도 소유자와 금액을 먼저 검증해야 다른 결제의 PG 조회를 막을 수 있다.
        Payment current = verifiedPayment(request, user.getId());

        boolean unresolved = current.getPaymentStatus().isRecoveryTarget();

        //미확정 시도는 최초 승인에 사용한 결제 키로만 복구할 수 있다.
        if (unresolved
                && !Objects.equals(current.getExternalPaymentId(), request.getExternalPaymentId())) {
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
        //사용자 API는 앞 단계에서 소유자를 확인한다. 스케줄러도 호출할 수 있도록 결제 PK로 잠근다.
        Payment current = paymentRepository.findLockedById(previous.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

        //PG 조회 도중 다른 결제 시도로 바뀌었다면 오래된 조회 결과를 반영하지 않는다.
        if (!Objects.equals(current.getProvider(), previous.getProvider())
                || !Objects.equals(current.getOrderId(), previous.getOrderId())
                || !Objects.equals(current.getExternalPaymentId(), previous.getExternalPaymentId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "결제 정보가 변경되었습니다.");
        }

        if (updateStatus && current.getPaymentStatus() == PaymentStatus.SUCCESS
                && result.getStatus() == PaymentStatus.SUCCESS
                && Objects.equals(current.getExternalPaymentId(), result.getExternalPaymentId())) {
            //다른 요청이 같은 결제 키의 성공을 먼저 확정했다면 이력은 중복 생성하지 않는다.
            saveApiLog(current, processor, result, apiType, requestedAt, durationMs, exception);
            return PaymentResponse.of(current);
        }

        //동시 조회에서 이미 반영한 대기 상태는 이력을 중복 기록하지 않는다.
        if (updateStatus && apiType == PaymentApiType.PAYMENT_LOOKUP
                && current.getPaymentStatus() == result.getStatus()
                && (result.getStatus() == PaymentStatus.PROCESSING
                || result.getStatus() == PaymentStatus.WAITING_FOR_PAYMENT)) {
            updateStatus = false;
        }

        //오래된 조회가 뒤늦게 끝나도 확인된 결제 대기를 다시 처리 중으로 되돌리지 않는다.
        if (updateStatus && current.getPaymentStatus() == PaymentStatus.WAITING_FOR_PAYMENT
                && result.getStatus() == PaymentStatus.PROCESSING) {
            updateStatus = false;
        }

        if (updateStatus) {
            PaymentStatus previousStatus = current.getPaymentStatus();

            //조회 중 다른 요청이 결제를 확정했다면 오래된 PG 결과로 다시 덮어쓰지 않는다.
            if (!previousStatus.isRecoveryTarget()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "결제 상태가 변경되었습니다.");
            }

            //정상 승인, 입금 대기, 실패 확정은 각 상태에 맞게 결제와 이력을 함께 변경한다.
            switch (result.getStatus()) {
                case SUCCESS -> {
                    current.approve(result.getExternalPaymentId(), result.getPaymentMethod(), result.getApprovedAt());
                }
                case PROCESSING, WAITING_FOR_PAYMENT -> {
                    current.pending(result.getStatus(), result.getExternalPaymentId(), result.getPaymentMethod());
                }
                case FAILED, UNKNOWN, PARTIAL_CANCELLED, CANCELLED -> {
                    current.fail(result.getStatus(), limit(result.getCode(), 100), limit(result.getMessage(), 500));
                }
                default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "저장할 수 없는 결제 상태입니다.");
            }

            //PG 결과에 따른 상태 변경 이력은 enum에 정의된 유형으로 동일하게 저장한다.
            saveHistory(current, result.getStatus().getPaymentHistoryType(),
                    previousStatus, result.getCode(), result.getMessage(), result.getTransactionId());
        }

        //조회 결과가 불확실한 경우에도 실제 PG 통신 기록은 남긴다.
        saveApiLog(current, processor, result, apiType, requestedAt, durationMs, exception);

        //상태 변경 여부와 관계없이 잠금 안에서 확인한 최신 결제를 응답으로 만든다.
        return PaymentResponse.of(current);
    }

    /**
     * 결제 완료 이벤트를 받으면 주문 반영을 시작하고 이미 완료된 이벤트는 건너뛴다.
     */
    @Transactional
    public boolean updateOrderPending(Long paymentId) {
        //중복 이벤트가 동시에 도착해도 상태 전이를 한 요청씩 처리하도록 결제 행을 잠근다.
        Payment payment = paymentRepository.findLockedById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

        //결제 성공이 확정된 건만 주문 반영 단계로 보낸다.
        if (payment.getPaymentStatus() != PaymentStatus.SUCCESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "주문을 반영할 수 없는 결제 상태입니다.");
        }

        //주문 반영이 끝난 결제는 재전달된 이벤트를 다시 처리하지 않는다.
        if (payment.getOrderStatus() == OrderStatus.COMPLETE) {
            return false;
        }

        //첫 이벤트에서만 BEFORE를 PENDING으로 바꾸고, 재전달된 PENDING은 그대로 처리한다.
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
        //주문 업무의 완료 통보와 중복 통보가 동시에 와도 상태를 안전하게 확정한다.
        Payment payment = paymentRepository.findLockedById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."));

        //이미 완료된 주문 반영은 중복 통보로 보고 종료한다.
        if (payment.getOrderStatus() == OrderStatus.COMPLETE) {
            return;
        }

        //결제 성공 뒤 이벤트가 수신되어 PENDING인 경우에만 완료를 허용한다.
        if (payment.getPaymentStatus() != PaymentStatus.SUCCESS
                || payment.getOrderStatus() != OrderStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "주문 반영을 완료할 수 없는 결제 상태입니다.");
        }

        //실제 주문 업무가 성공한 뒤 별도 트랜잭션에서 완료 시각과 상태를 기록한다.
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
        //진행 가능한 주문의 결제 행을 찾고, 요청한 사용자의 결제인지 확인한다.
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
                             PaymentStatus previousStatus, String code, String message,
                             String transactionId) {
        paymentHistoryRepository.save(PaymentHistory.builder()
                .paymentId(payment.getId())
                .userId(payment.getUserId())
                .loginId(payment.getLoginId())
                .historyType(type)
                .previousStatus(previousStatus)
                .currentStatus(payment.getPaymentStatus())
                .transactionAmount(type == PaymentHistoryType.PAYMENT_SUCCEEDED ? payment.getAmount() : null)
                .cumulativeCancelledAmount(payment.getCancelledAmount())
                .externalPaymentId(payment.getExternalPaymentId())
                .transactionId(transactionId)
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
                .externalPaymentId(result.getExternalPaymentId() == null
                        ? payment.getExternalPaymentId() : result.getExternalPaymentId())
                .transactionId(result.getTransactionId())
                .provider(payment.getProvider())
                .apiType(apiType)
                .httpMethod(apiType == PaymentApiType.PAYMENT_LOOKUP ? "GET" : processor.requestMethod().name())
                .requestUrl(apiType == PaymentApiType.PAYMENT_LOOKUP
                        ? processor.lookupUrl() : processor.paymentUrl())
                .responseStatus(result.getHttpStatus())
                .successYn(result.getHttpStatus() != null && result.getHttpStatus() >= 200
                        && result.getHttpStatus() < 300
                        && (result.getStatus() == PaymentStatus.SUCCESS
                        || result.getStatus() == PaymentStatus.PROCESSING
                        || result.getStatus() == PaymentStatus.WAITING_FOR_PAYMENT))
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
