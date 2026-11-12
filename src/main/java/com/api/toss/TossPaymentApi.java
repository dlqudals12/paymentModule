package com.api.toss;

import com.api.toss.dto.request.TossCancelRequest;
import com.api.toss.dto.request.TossConfirmRequest;
import com.api.toss.dto.request.TossVirtualAccountRequest;
import com.api.toss.dto.response.TossApiErrorResponse;
import com.api.toss.dto.response.TossApiResponse;
import com.api.toss.dto.response.TossApiResult;
import com.paymentModule.global.properties.payments.TossApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Component
public class TossPaymentApi {
    private final RestClient restClient;
    private final TossApiProperties tossApiProperties;

    /**
     * 토스 API의 기본 주소와 공통 인증 헤더를 설정한다.
     * 요청과 응답 로그에는 인증 헤더나 결제 본문을 기록하지 않는다.
     */
    public TossPaymentApi(TossApiProperties properties) {
        this.tossApiProperties = properties;

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                // 토스 서버 API는 시크릿 키 뒤에 ':'를 붙인 Basic 인증을 사용한다.
                .defaultHeaders(headers -> {
                    headers.setBasicAuth(properties.getSecretKey(), "");
                    headers.setContentType(MediaType.APPLICATION_JSON);
                })
                .requestInterceptor((request, body, execution) -> {
                    // 호출부가 재시도용 멱등키를 지정했다면 그대로 사용한다.
                    if (HttpMethod.POST.equals(request.getMethod())
                            && !request.getHeaders().containsHeader("Idempotency-Key")) {
                        request.getHeaders().set("Idempotency-Key", UUID.randomUUID().toString());
                    }

                    long startedAt = System.nanoTime();

                    // 인증 정보와 결제 본문은 로그에 남기지 않는다.
                    String path = request.getURI().getPath();
                    log.info("Toss API request: method={}, path={}", request.getMethod(), path);
                    try {
                        var response = execution.execute(request, body);

                        log.info("Toss API response: method={}, path={}, status={}, elapsedMs={}",
                                request.getMethod(), path, response.getStatusCode(),
                                (System.nanoTime() - startedAt) / 1_000_000);

                        return response;
                    } catch (IOException | RuntimeException e) {
                        log.error("Toss API request failed: method={}, path={}, elapsedMs={}",
                                request.getMethod(), path, (System.nanoTime() - startedAt) / 1_000_000, e);
                        throw e;
                    }
                })
                .build();
    }

    /**
     * 인증된 결제를 paymentKey, 주문번호, 금액으로 검증하고 최종 승인한다.
     * 재시도할 때는 최초 호출과 동일한 멱등키를 전달해야 한다.
     *
     * @param request        토스에서 발급한 결제키와 서버에서 확인한 주문번호 및 금액
     * @param idempotencyKey 동일 승인 요청의 재시도에 재사용할 키
     * @return 토스 HTTP 상태와 승인 결과 또는 오류 정보
     */
    public TossApiResult<TossApiResponse> confirm(TossConfirmRequest request, String idempotencyKey) {
        return execute(() -> restClient.post()
                .uri(tossApiProperties.getPath().confirm())
                .header("Idempotency-Key", Objects.requireNonNull(idempotencyKey))
                .body(request)
                .retrieve()
                .toEntity(TossApiResponse.class));
    }

    /**
     * 토스에서 발급한 paymentKey로 승인된 결제의 현재 상태를 조회한다.
     *
     * @param paymentKey 토스에서 발급한 결제 식별자
     * @return 토스 HTTP 상태와 조회 결과 또는 오류 정보
     */
    public TossApiResult<TossApiResponse> paymentByKey(String paymentKey) {
        return execute(() -> restClient.get()
                .uri(tossApiProperties.getPath().paymentKey(), paymentKey)
                .retrieve()
                .toEntity(TossApiResponse.class));
    }

    /**
     * 상점에서 생성한 orderId로 승인된 결제의 현재 상태를 조회한다.
     * 이 조회에는 API 개별 연동 시크릿 키가 필요하다.
     *
     * @param orderId 결제 요청 시 상점에서 생성한 주문번호
     * @return 토스 HTTP 상태와 조회 결과 또는 오류 정보
     */
    public TossApiResult<TossApiResponse> paymentByOrderId(String orderId) {
        return execute(() -> restClient.get()
                .uri(tossApiProperties.getPath().orderId(), orderId)
                .retrieve()
                .toEntity(TossApiResponse.class));
    }

    /**
     * paymentKey에 해당하는 결제를 전액 또는 일부 취소한다.
     * cancelAmount가 없으면 전액 취소이며, 재시도에는 같은 멱등키를 사용한다.
     *
     * @param paymentKey     취소할 결제의 토스 식별자
     * @param request        취소 사유, 부분 취소 금액과 필요한 경우 환불 계좌
     * @param idempotencyKey 동일 취소 요청의 재시도에 재사용할 키
     * @return 토스 HTTP 상태와 취소 결과 또는 오류 정보
     */
    public TossApiResult<TossApiResponse> cancel(String paymentKey, TossCancelRequest request, String idempotencyKey) {
        return execute(() -> restClient.post()
                .uri(tossApiProperties.getPath().cancel(), paymentKey)
                .header("Idempotency-Key", Objects.requireNonNull(idempotencyKey))
                .body(request)
                .retrieve()
                .toEntity(TossApiResponse.class));
    }

    /**
     * 구매자가 입금할 가상계좌를 발급하고 결제 상태를 반환한다.
     * 발급 요청을 재시도할 때는 최초 호출과 동일한 멱등키를 사용한다.
     *
     * @param request        주문 및 구매자 정보와 가상계좌 발급 조건
     * @param idempotencyKey 동일 발급 요청의 재시도에 재사용할 키
     * @return 토스 HTTP 상태와 발급 결과 또는 오류 정보
     */
    public TossApiResult<TossApiResponse> issueVirtualAccount(TossVirtualAccountRequest request, String idempotencyKey) {
        return execute(() -> restClient.post()
                .uri(tossApiProperties.getPath().virtualAccounts())
                .header("Idempotency-Key", Objects.requireNonNull(idempotencyKey))
                .body(request)
                .retrieve()
                .toEntity(TossApiResponse.class));
    }

    /**
     * 모든 토스 호출에 동일한 성공·실패 변환 규칙을 적용한다.
     * HTTP 오류는 토스 오류 본문을 읽고, 응답이 없는 통신 오류는 별도 코드로 구분한다.
     */
    private <T> TossApiResult<T> execute(Supplier<ResponseEntity<T>> call) {
        try {
            // 각 API 메서드가 전달한 HTTP 요청을 실행하고 상태 코드와 응답 본문을 함께 받는다.
            ResponseEntity<T> response = call.get();

            // Payment 응답을 기대하는 API이므로 본문이 없으면 502 실패로 처리한다.
            if (response.getBody() == null) {
                return TossApiResult.failure(HttpStatus.BAD_GATEWAY.value(),
                        "TOSS_EMPTY_RESPONSE", "토스 API 응답 본문이 없습니다.");
            }

            return TossApiResult.success(response.getStatusCode().value(), response.getBody());
        } catch (RestClientResponseException e) {
            // 토스가 4xx/5xx를 반환하면 오류 JSON의 code와 message를 우선 사용한다.
            TossApiErrorResponse error = errorBody(e);

            // 토스 오류 본문에 값이 없으면 예외 메시지를 남겨 실제 오류를 추적할 수 있게 한다.
            String code = error != null && error.getCode() != null && !error.getCode().isBlank()
                    ? error.getCode() : "TOSS_HTTP_ERROR";
            String message = error != null && error.getMessage() != null && !error.getMessage().isBlank()
                    ? error.getMessage() : exceptionMessage(e, "토스 API 오류 응답을 확인할 수 없습니다.");

            return TossApiResult.failure(e.getStatusCode().value(), code, message);
        } catch (ResourceAccessException e) {
            // 연결 실패나 타임아웃은 토스의 HTTP 응답이 없으므로 내부 대체 상태 코드 502를 사용한다.
            log.error("Toss API communication failed", e);

            return TossApiResult.failure(HttpStatus.BAD_GATEWAY.value(),
                    "TOSS_COMMUNICATION_ERROR", exceptionMessage(e, "토스 API 통신에 실패했습니다."));
        } catch (RestClientException e) {
            // 응답 변환 등 나머지 클라이언트 오류도 502로 반환하되 통신 실패와 코드를 구분한다.
            log.error("Toss API response could not be processed", e);

            return TossApiResult.failure(HttpStatus.BAD_GATEWAY.value(),
                    "TOSS_RESPONSE_ERROR", exceptionMessage(e, "토스 API 응답을 처리하지 못했습니다."));
        }
    }

    /**
     * 토스 오류 메시지를 받지 못했을 때 예외 원문을 사용하고, 원문도 비어 있으면 기본 문구를 반환한다.
     */
    private String exceptionMessage(Exception exception, String fallback) {
        return exception.getMessage() != null && !exception.getMessage().isBlank()
                ? exception.getMessage() : fallback;
    }

    /**
     * 토스 API v1의 오류 JSON을 파싱한다. 본문이 비어 있거나 형식이 다르면 null을 반환한다.
     */
    private TossApiErrorResponse errorBody(RestClientResponseException exception) {
        try {
            return exception.getResponseBodyAs(TossApiErrorResponse.class);
        } catch (RuntimeException e) {
            log.warn("Toss API error body could not be parsed: status={}", exception.getStatusCode(), e);
            return null;
        }
    }
}
