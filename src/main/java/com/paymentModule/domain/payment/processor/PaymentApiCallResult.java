package com.paymentModule.domain.payment.processor;

import com.paymentModule.data.enums.PaymentApiType;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.HttpMethod;

import java.time.LocalDateTime;

/**
 * PG API 호출 한 건의 로그 저장용 결과다. 여러 호출은 실행 순서대로 보관한다.
 */
@Getter
@Builder
public class PaymentApiCallResult {
    private PaymentApiType apiType;
    private HttpMethod httpMethod;
    private String requestUrl;
    /** 민감정보를 제외하고 JSON으로 직렬화한 요청 본문이다. 본문이 없는 호출은 null이다. */
    private String requestBody;
    private Integer httpStatus;
    /** 민감정보를 제외하고 JSON으로 직렬화한 응답 값이다. */
    private String responseBody;
    private String externalPaymentId;
    private String transactionId;
    private String code;
    private String message;
    private LocalDateTime requestedAt;
    private LocalDateTime respondedAt;
    private Long durationMs;
}
