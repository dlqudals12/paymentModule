package com.paymentModule.domain.payment.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentModule.api.toss.TossPaymentApi;
import com.paymentModule.api.toss.dto.request.TossConfirmRequest;
import com.paymentModule.api.toss.dto.response.TossApiResponse;
import com.paymentModule.api.toss.dto.response.TossApiResult;
import com.paymentModule.api.toss.enums.TossPaymentMethod;
import com.paymentModule.api.toss.enums.TossPaymentStatus;
import com.paymentModule.data.enums.PaymentApiType;
import com.paymentModule.data.enums.PaymentMethodType;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.global.exception.PaymentException;
import com.paymentModule.global.properties.payments.TossApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 토스 결제 처리기
 */
@Component
@RequiredArgsConstructor
public class TossPaymentProcessor implements PaymentProcessor {
    private static final ObjectMapper LOG_OBJECT_MAPPER = new ObjectMapper();

    private final TossPaymentApi tossPaymentApi;
    private final TossApiProperties tossApiProperties;

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.TOSS;
    }

    /**
     * 토스 주문번호 검증
     */
    @Override
    public void orderIdValidation(PaymentReadyRequest request) {
        String orderId = request.getOrderId();
        if (orderId.length() < 6 || !orderId.matches("[A-Za-z0-9_=-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "토스 주문번호 형식이 올바르지 않습니다.");
        }
    }

    /**
     * 토스 승인값 검증
     */
    @Override
    public void paymentValidation(PaymentRequest request) {
        if (!StringUtils.hasText(request.getExternalPaymentId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "토스 결제 키가 필요합니다.");
        }

        if (request.getOrderId().length() < 6 || !request.getOrderId().matches("[A-Za-z0-9_=-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "토스 주문번호 형식이 올바르지 않습니다.");
        }
    }

    /**
     * 토스 결제 승인
     */
    @Override
    public PaymentProcessResult payment(PaymentProcessorData payment, PaymentRequest request) {
        String paymentKey = request.getExternalPaymentId();
        String requestUrl = tossApiProperties.getBaseUrl() + tossApiProperties.getPath().confirm();
        LocalDateTime requestedAt = LocalDateTime.now();
        long startedAt = System.nanoTime();

        //준비 단계에서 저장한 금액으로 토스 승인 요청
        TossConfirmRequest confirmRequest = TossConfirmRequest.builder()
                .paymentKey(paymentKey)
                .orderId(payment.getOrderId())
                .amount(payment.getAmount())
                .build();
        TossApiResult<TossApiResponse> result = tossPaymentApi.confirm(
                confirmRequest, payment.getIdempotencyKey());

        //통신 오류 등 토스의 HTTP 응답이 없었다면 응답 상태 코드를 기록하지 않는다.
        Integer httpStatus = switch (result.getCode()) {
            case "TOSS_COMMUNICATION_ERROR", "TOSS_RESPONSE_ERROR", "TOSS_EMPTY_RESPONSE" -> null;
            default -> result.getHttpStatus();
        };
        PaymentApiCallResult apiCall = apiCall(PaymentApiType.PAYMENT, HttpMethod.POST,
                requestUrl, requestBody(confirmRequest), paymentKey,
                result, httpStatus, requestedAt, startedAt);

        if (result.getData() == null) {
            //PG API 오류는 서비스에서 DB 상태를 기록할 수 있도록 공통 결과를 예외에 담는다.
            //응답 없는 통신 오류와 PG 서버 오류는 승인 여부를 확인할 수 없어 UNKNOWN이다.
            throw new PaymentException(PaymentProcessResult.builder()
                    .apiCalls(List.of(apiCall))
                    .httpStatus(httpStatus)
                    .status(result.getHttpStatus() >= 500 ? PaymentStatus.UNKNOWN : PaymentStatus.FAILED)
                    .code(result.getCode())
                    .message(result.getMessage())
                    .build());
        }

        TossApiResponse approved = result.getData();

        //토스 응답과 저장된 결제 정보 비교
        if (!payment.getOrderId().equals(approved.getOrderId())
                || !payment.getAmount().equals(approved.getTotalAmount())
                || !paymentKey.equals(approved.getPaymentKey())) {
            return PaymentProcessResult.builder()
                    .apiCalls(List.of(apiCall))
                    .httpStatus(httpStatus)
                    .status(PaymentStatus.UNKNOWN)
                    .code("TOSS_RESULT_MISMATCH")
                    .message("토스 승인 결과가 준비된 결제 정보와 다릅니다.")
                    .build();
        }

        //토스 결제 상태에 따라 공통 결과 생성
        if (approved.getStatus() == TossPaymentStatus.DONE) {
            return resultBuilder(apiCall, httpStatus, approved)
                    .status(PaymentStatus.SUCCESS)
                    .approvedAt(approvedAt(approved.getApprovedAt()))
                    .build();
        }

        if (approved.getStatus() == TossPaymentStatus.WAITING_FOR_DEPOSIT) {
            // 입금 대기 상태는 결제 완료가 아니므로 이벤트를 발행하지 않는다.
            return resultBuilder(apiCall, httpStatus, approved)
                    .status(PaymentStatus.WAITING_FOR_PAYMENT)
                    .build();
        }

        //토스 상태에 정의한 공통 결제 상태 사용
        PaymentStatus status = approved.getStatus() == null
                ? PaymentStatus.UNKNOWN : approved.getStatus().getPaymentStatus();
        if (status == PaymentStatus.READY) {
            //승인 응답에서 초기 상태가 돌아오면 실제 승인 여부를 다시 확인해야 한다.
            status = PaymentStatus.UNKNOWN;
        }

        return resultBuilder(apiCall, httpStatus, approved)
                .status(status)
                .code("TOSS_NOT_DONE")
                .message("토스 결제가 완료 상태가 아닙니다: " + approved.getStatus())
                .build();
    }

    /**
     * 같은 결제 키로 토스 상태를 조회하고 저장된 주문 정보와 대조한다.
     */
    @Override
    public PaymentProcessResult lookup(PaymentProcessorData payment, String paymentKey) {
        // 조회 경로의 결제 키를 실제 요청값으로 치환해 호출 URL을 결과에 남긴다.
        String requestUrl = tossApiProperties.getBaseUrl()
                + tossApiProperties.getPath().paymentKey().replace("{paymentKey}", paymentKey);
        LocalDateTime requestedAt = LocalDateTime.now();
        long startedAt = System.nanoTime();
        TossApiResult<TossApiResponse> result = tossPaymentApi.paymentByKey(paymentKey);

        //토스의 HTTP 응답이 없었던 오류는 내부 대체 상태 코드를 PG 응답으로 기록하지 않는다.
        Integer httpStatus = switch (result.getCode()) {
            case "TOSS_COMMUNICATION_ERROR", "TOSS_RESPONSE_ERROR", "TOSS_EMPTY_RESPONSE" -> null;
            default -> result.getHttpStatus();
        };
        PaymentApiCallResult apiCall = apiCall(PaymentApiType.PAYMENT_LOOKUP, HttpMethod.GET,
                requestUrl, null, paymentKey, result, httpStatus, requestedAt, startedAt);

        if (result.getData() == null) {
            //토스가 결제 정보 부재를 명시한 경우에만 새 시도를 허용할 실패 후보로 반환한다.
            boolean notFound = httpStatus != null && httpStatus == 404
                    && ("NOT_FOUND_PAYMENT".equals(result.getCode())
                    || "NOT_FOUND".equals(result.getCode()));

            //그 밖의 조회 오류는 결제 부재로 단정하지 않고 서비스에 UNKNOWN으로 전달한다.
            throw new PaymentException(PaymentProcessResult.builder()
                    .apiCalls(List.of(apiCall))
                    .httpStatus(httpStatus)
                    .status(notFound ? PaymentStatus.FAILED : PaymentStatus.UNKNOWN)
                    .code(result.getCode())
                    .message(result.getMessage())
                    .build());
        }

        TossApiResponse response = result.getData();

        if (!payment.getOrderId().equals(response.getOrderId())
                || !payment.getAmount().equals(response.getTotalAmount())
                || !paymentKey.equals(response.getPaymentKey())) {
            return PaymentProcessResult.builder()
                    .apiCalls(List.of(apiCall))
                    .httpStatus(httpStatus)
                    .status(PaymentStatus.UNKNOWN)
                    .code("TOSS_RESULT_MISMATCH")
                    .message("토스 조회 결과가 준비된 결제 정보와 다릅니다.")
                    .build();
        }

        PaymentStatus status = response.getStatus() == null
                ? PaymentStatus.UNKNOWN : response.getStatus().getPaymentStatus();

        if (status == PaymentStatus.READY) {
            status = PaymentStatus.PROCESSING;
        }

        return resultBuilder(apiCall, httpStatus, response)
                .status(status)
                .approvedAt(status == PaymentStatus.SUCCESS ? approvedAt(response.getApprovedAt()) : null)
                .code(status == PaymentStatus.SUCCESS ? null : "TOSS_LOOKUP_" + response.getStatus())
                .message(status == PaymentStatus.SUCCESS ? null : "토스 결제 조회 상태: " + response.getStatus())
                .build();
    }

    /**
     * 검증된 토스 응답에서 모든 결제수단에 공통으로 전달할 결과 값을 구성한다.
     */
    private PaymentProcessResult.PaymentProcessResultBuilder resultBuilder(
            PaymentApiCallResult apiCall, Integer httpStatus, TossApiResponse response) {
        return PaymentProcessResult.builder()
                .apiCalls(List.of(apiCall))
                .httpStatus(httpStatus)
                .externalPaymentId(response.getPaymentKey())
                .transactionId(transactionKey(response))
                .paymentMethod(paymentMethod(response.getMethod()))
                .providerStatus(response.getStatus() == null ? null : response.getStatus().name())
                .providerMethod(providerMethod(response.getMethod()))
                .requestedAt(pgDate(response.getRequestedAt()))
                .receiptUrl(receiptUrl(response))
                .card(card(response.getCard()))
                .transfer(transfer(response.getTransfer()))
                .virtualAccount(virtualAccount(response.getVirtualAccount()))
                .easyPay(easyPay(response.getEasyPay()))
                .cashReceipt(cashReceipt(response.getCashReceipt()));
    }

    /**
     * 토스 API 호출 한 건의 메서드, URL, 응답과 소요 시간을 로그 값으로 만든다.
     */
    private PaymentApiCallResult apiCall(PaymentApiType apiType, HttpMethod httpMethod,
                                         String requestUrl, String requestBody, String paymentKey,
                                         TossApiResult<TossApiResponse> result, Integer httpStatus,
                                         LocalDateTime requestedAt, long startedAt) {
        TossApiResponse response = result.getData();

        return PaymentApiCallResult.builder()
                .apiType(apiType)
                .httpMethod(httpMethod)
                .requestUrl(requestUrl)
                .requestBody(requestBody)
                .httpStatus(httpStatus)
                .responseBody(responseBody(result))
                .externalPaymentId(response == null ? paymentKey : response.getPaymentKey())
                .transactionId(response == null ? null : transactionKey(response))
                .code("SUCCESS".equals(result.getCode()) ? null : result.getCode())
                .message("SUCCESS".equals(result.getCode()) ? null : result.getMessage())
                .requestedAt(requestedAt)
                .respondedAt(httpStatus == null ? null : LocalDateTime.now())
                .durationMs((System.nanoTime() - startedAt) / 1_000_000)
                .build();
    }

    /**
     * 승인 요청에서 주문번호와 금액만 JSON으로 남긴다. 결제 키는 로그의 별도 식별 컬럼에 기록한다.
     */
    private String requestBody(TossConfirmRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderId", request.getOrderId());
        fields.put("amount", request.getAmount());
        return LOG_OBJECT_MAPPER.valueToTree(fields).toString();
    }

    /**
     * 토스 응답에서 상태 확인에 필요한 값만 JSON으로 남긴다.
     * 결제수단의 카드·계좌·휴대전화 상세 정보와 원문은 포함하지 않는다.
     */
    private String responseBody(TossApiResult<TossApiResponse> result) {
        Map<String, Object> fields = new LinkedHashMap<>();

        if (result.getData() == null) {
            fields.put("code", result.getCode());
            fields.put("message", result.getMessage());
        } else {
            TossApiResponse response = result.getData();
            fields.put("orderId", response.getOrderId());
            fields.put("totalAmount", response.getTotalAmount());
            fields.put("status", response.getStatus());
            fields.put("method", response.getMethod());
            fields.put("approvedAt", response.getApprovedAt());
        }

        return LOG_OBJECT_MAPPER.valueToTree(fields).toString();
    }

    /**
     * 결제 키와 별개인 토스의 최근 거래 키를 이력·통신 로그용으로 추출한다.
     */
    private String transactionKey(TossApiResponse response) {
        //구 응답의 transactionKey는 최신 lastTransactionKey가 없을 때만 사용한다.
        return StringUtils.hasText(response.getLastTransactionKey())
                ? response.getLastTransactionKey() : response.getTransactionKey();
    }

    /**
     * 결제수단 변환
     */
    private PaymentMethodType paymentMethod(TossPaymentMethod method) {
        if (method == null) {
            return null;
        }

        return method.getPaymentMethod();
    }

    /**
     * 공통 결제수단과 별도로 토스가 응답한 수단 값을 유지한다.
     */
    private String providerMethod(TossPaymentMethod method) {
        return method == null ? null : LOG_OBJECT_MAPPER.valueToTree(method).asText();
    }

    /**
     * 토스 카드 응답을 PG에 종속되지 않는 카드 정보로 변환한다.
     */
    private PaymentCardInfo card(TossApiResponse.Card source) {
        if (source == null) {
            return null;
        }

        return PaymentCardInfo.builder()
                .issuerCode(source.getIssuerCode())
                .acquirerCode(source.getAcquirerCode())
                .maskedNumber(source.getNumber())
                .installmentMonths(source.getInstallmentPlanMonths())
                .approvalNumber(source.getApproveNo())
                .interestFree(source.getIsInterestFree())
                .cardType(source.getCardType() == null ? null : source.getCardType().name())
                .ownerType(source.getOwnerType() == null ? null : source.getOwnerType().name())
                .cardPointUsed(source.getUseCardPoint())
                .build();
    }

    /**
     * 토스 계좌이체 응답의 은행 정보를 변환한다.
     */
    private PaymentTransferInfo transfer(TossApiResponse.Transfer source) {
        return source == null ? null : PaymentTransferInfo.builder()
                .bankCode(source.getBankCode())
                .build();
    }

    /**
     * 토스가 발급한 가상계좌의 입금 안내 정보를 변환한다.
     */
    private PaymentVirtualAccountInfo virtualAccount(TossApiResponse.VirtualAccount source) {
        if (source == null) {
            return null;
        }

        return PaymentVirtualAccountInfo.builder()
                .bankCode(source.getBankCode())
                .accountNumber(source.getAccountNumber())
                .depositorName(source.getDepositorName())
                .dueDate(source.getDueDate())
                .expired(source.getExpired())
                .build();
    }

    /**
     * 토스 간편결제 제공사와 사용 금액을 변환한다.
     */
    private PaymentEasyPayInfo easyPay(TossApiResponse.EasyPay source) {
        return source == null ? null : PaymentEasyPayInfo.builder()
                .provider(source.getProvider())
                .amount(source.getAmount())
                .discountAmount(source.getDiscountAmount())
                .build();
    }

    /**
     * 토스가 제공한 현금영수증 발급 식별 정보만 반환한다.
     */
    private PaymentCashReceiptInfo cashReceipt(TossApiResponse.CashReceipt source) {
        return source == null ? null : PaymentCashReceiptInfo.builder()
                .type(source.getType() == null ? null : source.getType().name())
                .issueNumber(source.getIssueNumber())
                .receiptUrl(source.getReceiptUrl())
                .build();
    }

    /**
     * 결제 영수증 주소가 없는 결제수단은 null로 둔다.
     */
    private String receiptUrl(TossApiResponse response) {
        return response.getReceipt() == null ? null : response.getReceipt().getUrl();
    }

    /**
     * PG 시각을 한국 시각으로 변환한다. 값이 없거나 형식을 알 수 없으면 비워 둔다.
     */
    private LocalDateTime pgDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * 승인 시각 변환
     */
    private LocalDateTime approvedAt(String value) {
        if (value == null) {
            return LocalDateTime.now();
        }

        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime();
        } catch (DateTimeParseException exception) {
            // 승인 성공 자체는 유지하고, 시각 형식에 문제가 있으면 서버 수신 시각을 사용한다.
            return LocalDateTime.now();
        }
    }
}
