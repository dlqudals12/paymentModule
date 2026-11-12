package com.api.toss.dto.request;

import lombok.Builder;
import lombok.Getter;

/**
 * 결제 승인 API에 전달하는 필수 값이다.
 * amount는 클라이언트 값만 신뢰하지 말고 서버에 저장한 주문 금액과 대조해야 한다.
 */
@Getter
@Builder
public class TossConfirmRequest {
    private String paymentKey;

    private String orderId;

    private Long amount;
}
