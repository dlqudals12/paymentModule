package com.paymentModule.api.nice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentModule.api.nice.dto.response.NiceApiResponse;
import com.paymentModule.api.nice.dto.response.NiceApiResult;
import com.paymentModule.global.properties.payments.NiceApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 나이스페이 Server 승인 모델의 승인 POST와 거래 조회 GET을 호출한다.
 */
@Slf4j
@Component
public class NicePaymentApi {
    private static final ObjectMapper LOG_OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final NiceApiProperties properties;

    /**
     * 결제창의 공개 키와 서버 시크릿 키를 Basic 인증에 함께 사용한다.
     */
    public NicePaymentApi(NiceApiProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                // 로그에서 응답 본문을 읽어도 JSON 변환기가 다시 읽을 수 있게 한다.
                .requestFactory(new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()))
                .defaultHeaders(headers -> headers.setBasicAuth(properties.getClientKey(), properties.getSecretKey()))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor((request, body, execution) -> {
                    long startedAt = System.nanoTime();
                    String path = request.getURI().getPath();
                    log.info("\nNice API request\n  method={}\n  path={}\n  headers={}\n  body={}",
                            request.getMethod(), path, logHeaders(request.getHeaders()), logBody(body));
                    try {
                        var response = execution.execute(request, body);
                        log.info("\nNice API response\n  method={}\n  path={}\n  status={}\n  elapsedMs={}\n  headers={}\n  body={}",
                                request.getMethod(), path, response.getStatusCode(),
                                (System.nanoTime() - startedAt) / 1_000_000,
                                logHeaders(response.getHeaders()), logBody(response.getBody().readAllBytes()));
                        return response;
                    } catch (IOException | RuntimeException exception) {
                        log.error("\nNice API request failed\n  method={}\n  path={}\n  elapsedMs={}",
                                request.getMethod(), path, (System.nanoTime() - startedAt) / 1_000_000, exception);
                        throw exception;
                    }
                })
                .build();
    }

    /**
     * Basic 인증 헤더와 쿠키는 콘솔에도 출력하지 않는다.
     */
    private Map<String, Object> logHeaders(HttpHeaders headers) {
        Map<String, Object> visible = new LinkedHashMap<>();
        headers.forEach((name, values) -> visible.put(name,
                sensitive(name) ? "[REDACTED]" : values));
        return visible;
    }

    /**
     * JSON 본문은 여러 줄로 표시하고 인증 관련 필드는 중첩 객체에서도 가린다.
     */
    private String logBody(byte[] body) {
        if (body.length == 0) return "[empty]";
        try {
            JsonNode json = LOG_OBJECT_MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
            if (json == null) return "[empty]";
            redact(json);
            return json.toPrettyString();
        } catch (IOException exception) {
            return "[non-JSON body omitted]";
        }
    }

    /**
     * 원본 응답의 인증 토큰과 구매자 식별 키를 콘솔에서 제외한다.
     */
    private void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                if (sensitive(entry.getKey())) object.put(entry.getKey(), "[REDACTED]");
                else redact(entry.getValue());
            });
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::redact);
        }
    }

    /**
     * 로그에 남기면 안 되는 헤더와 JSON 필드를 이름으로 판별한다.
     */
    private boolean sensitive(String name) {
        String field = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return field.contains("authorization") || field.contains("cookie")
                || field.contains("secret") || field.contains("token")
                || field.contains("apikey") || field.contains("customerkey")
                || field.contains("billingkey");
    }

    /**
     * SDK 인증 결과의 tid로 결제를 승인한다. 금액은 서버가 준비한 값을 보낸다.
     */
    public NiceApiResult confirm(String tid, Long amount) {
        return execute(() -> restClient.post()
                .uri(properties.getPath().payment(), tid)
                .body(Map.of("amount", amount))
                .retrieve()
                .toEntity(NiceApiResponse.class));
    }

    /**
     * 승인 응답 유실 또는 결제 대기 상태를 tid로 재조회한다.
     */
    public NiceApiResult payment(String tid) {
        return execute(() -> restClient.get()
                .uri(properties.getPath().payment(), tid)
                .retrieve()
                .toEntity(NiceApiResponse.class));
    }

    /**
     * 나이스페이 JSON 오류와 응답 없는 통신 실패를 구별한다.
     */
    private NiceApiResult execute(Supplier<ResponseEntity<NiceApiResponse>> call) {
        try {
            ResponseEntity<NiceApiResponse> response = call.get();
            if (response.getBody() == null) {
                return NiceApiResult.failure(response.getStatusCode().value(),
                        "NICE_EMPTY_RESPONSE", "나이스페이 응답 본문이 없습니다.");
            }
            return NiceApiResult.success(response.getStatusCode().value(), response.getBody());
        } catch (RestClientResponseException exception) {
            // PG 오류 원문에는 구매자 정보가 있을 수 있어 전체 본문은 로그에 남기지 않는다.
            String code = "NICE_HTTP_ERROR";
            String message = "나이스페이 API 요청이 거절되었습니다.";
            try {
                NiceApiResponse error = exception.getResponseBodyAs(NiceApiResponse.class);
                if (error != null) {
                    if (error.getResultCode() != null) code = error.getResultCode();
                    if (error.getResultMsg() != null) message = error.getResultMsg();
                }
            } catch (RuntimeException ignored) {
                log.warn("Nice API error body could not be parsed: status={}", exception.getStatusCode());
            }
            return NiceApiResult.failure(exception.getStatusCode().value(), code, message);
        } catch (ResourceAccessException exception) {
            log.error("Nice API communication failed", exception);
            return NiceApiResult.failure(null, "NICE_COMMUNICATION_ERROR", "나이스페이 API 통신에 실패했습니다.");
        } catch (RestClientException exception) {
            log.error("Nice API response could not be processed", exception);
            return NiceApiResult.failure(null, "NICE_RESPONSE_ERROR", "나이스페이 응답을 처리하지 못했습니다.");
        }
    }
}
