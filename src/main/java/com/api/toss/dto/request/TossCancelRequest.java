package com.api.toss.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

/**
 * 결제 취소 요청 본문이다.
 * cancelAmount를 생략하면 전액 취소하고, 입금 완료된 가상계좌 결제는 환불 계좌가 필요하다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Builder
public class TossCancelRequest {
    private String cancelReason;

    private Long cancelAmount;

    private RefundReceiveAccount refundReceiveAccount;
    private Long taxFreeAmount;
    private String currency;
    private Long refundableAmount;
    private String cancelRequestId;

    /**
     * 가상계좌 결제 취소 시 환불받을 계좌 정보다.
     */
    @Getter
    @Builder
    public static class RefundReceiveAccount {
        private String bank;
        private String accountNumber;
        private String holderName;
    }
}
