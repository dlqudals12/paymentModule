package com.paymentModule.domain.payment.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentModule.api.nice.NicePaymentApi;
import com.paymentModule.api.nice.NiceSignatureVerifier;
import com.paymentModule.api.nice.dto.response.NiceApiResponse;
import com.paymentModule.api.nice.dto.response.NiceApiResult;
import com.paymentModule.api.nice.enums.NicePaymentStatus;
import com.paymentModule.data.enums.PaymentApiType;
import com.paymentModule.data.enums.PaymentMethodType;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.global.exception.PaymentException;
import com.paymentModule.global.properties.payments.NiceApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 나이스페이 결제창 인증 결과를 승인·조회하고 공통 결제 결과로 변환한다.
 */
@Component
@RequiredArgsConstructor
public class NicePaymentProcessor implements PaymentProcessor {
    private static final ObjectMapper LOG_OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter NICE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private final NicePaymentApi nicePaymentApi;
    private final NiceApiProperties properties;
    private final NiceSignatureVerifier signatureVerifier;

    /**
     * 나이스페이 처리기를 Registry의 NICE 키에 등록한다.
     */
    @Override
    public PaymentProvider provider() {
        return PaymentProvider.NICE;
    }

    /**
     * NICEPAY 주문번호는 결제창 요청 한도인 64자 이내의 값이어야 한다.
     */
    @Override
    public void orderIdValidation(PaymentReadyRequest request) {
        if (!StringUtils.hasText(request.getOrderId()) || request.getOrderId().length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "나이스페이 주문번호가 올바르지 않습니다.");
        }
    }

    /**
     * 프런트가 전달한 SDK 인증 결과의 서명을 승인 요청 전에 검증한다.
     */
    @Override
    public void paymentValidation(PaymentRequest request) {
        if (!StringUtils.hasText(request.getExternalPaymentId())
                || !signatureVerifier.valid(request.getAuthToken(), request.getClientId(),
                String.valueOf(request.getAmount()), request.getSignature())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "나이스페이 인증 결과가 올바르지 않습니다.");
        }
    }

    /**
     * 준비 단계 금액으로 NICEPAY에 승인 POST를 보내고 결제 상태를 확인한다.
     */
    @Override
    public PaymentProcessResult payment(PaymentProcessorData payment, PaymentRequest request) {
        String tid = request.getExternalPaymentId();
        LocalDateTime requestedAt = LocalDateTime.now();
        long startedAt = System.nanoTime();
        NiceApiResult result = nicePaymentApi.confirm(tid, payment.getAmount());
        PaymentApiCallResult call = apiCall(PaymentApiType.PAYMENT, HttpMethod.POST, tid,
                payment.getAmount(), result, requestedAt, startedAt);

        if (result.data() == null) {
            // 응답이 없거나 서버 오류면 실제 승인 여부를 단정하지 않고 조회 복구 대상으로 둔다.
            PaymentStatus status = result.httpStatus() == null || result.httpStatus() >= 500
                    ? PaymentStatus.UNKNOWN : PaymentStatus.FAILED;
            throw new PaymentException(error(call, result.httpStatus(), status, result.code(), result.message()));
        }

        return translate(payment, tid, result, call, true);
    }

    /**
     * 저장한 tid로 거래 조회 GET을 호출해 결과 미확인 결제를 복구한다.
     */
    @Override
    public PaymentProcessResult lookup(PaymentProcessorData payment, String tid) {
        LocalDateTime requestedAt = LocalDateTime.now();
        long startedAt = System.nanoTime();
        NiceApiResult result = nicePaymentApi.payment(tid);
        PaymentApiCallResult call = apiCall(PaymentApiType.PAYMENT_LOOKUP, HttpMethod.GET, tid,
                null, result, requestedAt, startedAt);

        if (result.data() == null) {
            // 조회 경로가 명시적으로 404를 반환한 경우에만 결제 부재로 분류한다.
            PaymentStatus status = result.httpStatus() != null && result.httpStatus() == 404
                    ? PaymentStatus.FAILED : PaymentStatus.UNKNOWN;
            throw new PaymentException(error(call, result.httpStatus(), status, result.code(), result.message()));
        }

        return translate(payment, tid, result, call, false);
    }

    /**
     * PG 거래 키·주문번호·금액을 모두 확인한 뒤 원본 상태를 공통 상태로 바꾼다.
     */
    private PaymentProcessResult translate(PaymentProcessorData payment, String tid,
                                           NiceApiResult result, PaymentApiCallResult call, boolean approval) {
        NiceApiResponse response = result.data();
        if (!"0000".equals(response.getResultCode())) {
            // 승인 API가 JSON으로 명시한 실패는 결제 불일치와 구분해 확정 실패로 기록한다.
            PaymentStatus status = approval ? PaymentStatus.FAILED : PaymentStatus.UNKNOWN;
            return error(call, result.httpStatus(), status,
                    response.getResultCode(), response.getResultMsg());
        }

        if (!tid.equals(response.getTid())
                || !payment.getOrderId().equals(response.getOrderId())
                || !payment.getAmount().equals(response.getAmount())) {
            return error(call, result.httpStatus(), PaymentStatus.UNKNOWN,
                    "NICE_RESULT_MISMATCH", "나이스페이 결과가 준비된 결제 정보와 다릅니다.");
        }

        NicePaymentStatus original = NicePaymentStatus.of(response.getStatus());
        PaymentStatus status = original == null ? PaymentStatus.UNKNOWN : original.getPaymentStatus();
        if (original == NicePaymentStatus.READY && "vbank".equalsIgnoreCase(response.getPayMethod())) {
            // 발급된 가상계좌의 입금을 기다리는 상태는 처리 중과 구분한다.
            status = PaymentStatus.WAITING_FOR_PAYMENT;
        } else if (original == NicePaymentStatus.READY && approval) {
            // 승인 POST 뒤 일반 결제가 여전히 준비 상태라면 결과를 다시 확인해야 한다.
            status = PaymentStatus.UNKNOWN;
        }

        return PaymentProcessResult.builder()
                .apiCalls(List.of(call))
                .httpStatus(result.httpStatus())
                .status(status)
                .externalPaymentId(response.getTid())
                .transactionId(response.getTid())
                .paymentMethod(paymentMethod(response.getPayMethod()))
                .providerStatus(response.getStatus())
                .providerMethod(response.getPayMethod())
                .approvedAt(status == PaymentStatus.SUCCESS ? paidAt(response.getPaidAt()) : null)
                .receiptUrl(response.getReceiptUrl())
                .card(card(response))
                .transfer(transfer(response))
                .virtualAccount(virtualAccount(response))
                .easyPay(easyPay(response))
                .code(status == PaymentStatus.SUCCESS ? null : response.getResultCode())
                .message(status == PaymentStatus.SUCCESS ? null : response.getResultMsg())
                .build();
    }

    /**
     * 실패 정보를 API 로그와 결제 이력에서 사용할 공통 결과로 묶는다.
     */
    private PaymentProcessResult error(PaymentApiCallResult call, Integer httpStatus,
                                       PaymentStatus status, String code, String message) {
        return PaymentProcessResult.builder()
                .apiCalls(List.of(call))
                .httpStatus(httpStatus)
                .status(status)
                .code(code)
                .message(message)
                .build();
    }

    /**
     * 호출별 URL과 필수 추적 필드만 기록한다. Basic 인증과 인증 토큰은 저장하지 않는다.
     */
    private PaymentApiCallResult apiCall(PaymentApiType type, HttpMethod method, String tid,
                                         Long amount, NiceApiResult result,
                                         LocalDateTime requestedAt, long startedAt) {
        NiceApiResponse response = result.data();
        String requestBody = amount == null ? null : json(Map.of("amount", amount));
        String responseBody = null;
        if (response != null) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("resultCode", response.getResultCode());
            fields.put("status", response.getStatus());
            fields.put("orderId", response.getOrderId());
            fields.put("amount", response.getAmount());
            responseBody = json(fields);
        }

        return PaymentApiCallResult.builder()
                .apiType(type)
                .httpMethod(method)
                .requestUrl(properties.getBaseUrl() + properties.getPath().payment().replace("{tid}", tid))
                .requestBody(requestBody)
                .httpStatus(result.httpStatus())
                .responseBody(responseBody)
                .externalPaymentId(tid)
                .transactionId(response == null ? null : response.getTid())
                .code(response == null ? result.code() : response.getResultCode())
                .message(response == null ? result.message() : response.getResultMsg())
                .requestedAt(requestedAt)
                .respondedAt(result.httpStatus() == null ? null : LocalDateTime.now())
                .durationMs((System.nanoTime() - startedAt) / 1_000_000)
                .build();
    }

    /**
     * 원문 대신 선별한 로그 필드만 JSON 문자열로 바꾼다.
     */
    private String json(Map<String, Object> fields) {
        return LOG_OBJECT_MAPPER.valueToTree(fields).toString();
    }

    /**
     * 나이스페이 결제수단을 공통 결제수단으로 변환한다.
     */
    private PaymentMethodType paymentMethod(String method) {
        if (method == null) return null;
        return switch (method.toLowerCase()) {
            case "card" -> PaymentMethodType.CARD;
            case "bank" -> PaymentMethodType.TRANSFER;
            case "vbank" -> PaymentMethodType.VIRTUAL_ACCOUNT;
            case "naverpay", "kakaopay", "payco", "ssgpay", "samsungpay" -> PaymentMethodType.EASY_PAY;
            default -> null;
        };
    }

    /**
     * 카드 응답의 번호는 PG가 이미 가린 값만 보관한다.
     */
    private PaymentCardInfo card(NiceApiResponse response) {
        NiceApiResponse.Card card = response.getCard();
        if (card == null) return null;
        return PaymentCardInfo.builder()
                .issuerCode(card.getCardCode())
                .issuerName(card.getCardName())
                .acquirerCode(card.getAcquCardCode())
                .acquirerName(card.getAcquCardName())
                .maskedNumber(card.getCardNum())
                .installmentMonths(card.getCardQuota())
                .approvalNumber(response.getApproveNo())
                .cardType(card.getCardType())
                .build();
    }

    /**
     * 계좌이체 은행과 승인번호를 공통 상세 정보로 옮긴다.
     */
    private PaymentTransferInfo transfer(NiceApiResponse response) {
        NiceApiResponse.Bank bank = response.getBank();
        if (bank == null) return null;
        return PaymentTransferInfo.builder()
                .bankCode(bank.getBankCode())
                .bankName(bank.getBankName())
                .approvalNumber(response.getApproveNo())
                .build();
    }

    /**
     * 가상계좌 발급 시 고객에게 다시 보여줄 입금 안내만 옮긴다.
     */
    private PaymentVirtualAccountInfo virtualAccount(NiceApiResponse response) {
        NiceApiResponse.VirtualAccount account = response.getVbank();
        if (account == null) return null;
        return PaymentVirtualAccountInfo.builder()
                .bankCode(account.getVbankCode())
                .bankName(account.getVbankName())
                .accountNumber(account.getVbankNumber())
                .accountHolderName(account.getVbankHolder())
                .dueDate(account.getVbankExpDate())
                .build();
    }

    /**
     * 간편결제 제공사 이름은 NICEPAY 원본 수단 값으로 구별한다.
     */
    private PaymentEasyPayInfo easyPay(NiceApiResponse response) {
        return paymentMethod(response.getPayMethod()) == PaymentMethodType.EASY_PAY
                ? PaymentEasyPayInfo.builder().provider(response.getPayMethod()).build() : null;
    }

    /**
     * NICEPAY의 타임존 포함 승인 시각을 서버 로컬 시각으로 변환한다.
     */
    private LocalDateTime paidAt(String value) {
        if (!StringUtils.hasText(value) || "0".equals(value)) return null;
        try {
            return OffsetDateTime.parse(value, NICE_DATE).toLocalDateTime();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
