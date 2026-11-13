package com.paymentModule.api.toss.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paymentModule.api.toss.enums.TossCashReceiptType;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 가상계좌 발급 요청 본문이다.
 * validHours와 dueDate는 입금 기한을 정하는 두 방식이므로 한 가지만 지정한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Builder
public class TossVirtualAccountRequest {
    private Long amount;

    private String orderId;

    private String orderName;

    private String customerName;

    private String bank;

    private String accountKey;
    private Integer validHours;
    private String dueDate;
    private String customerEmail;
    private String customerMobilePhone;
    private Long taxFreeAmount;
    private Boolean useEscrow;
    private CashReceipt cashReceipt;
    private List<EscrowProduct> escrowProducts;

    /**
     * 현금영수증 발급 용도와 소비자 식별 번호다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter
    @Builder
    public static class CashReceipt {
        private TossCashReceiptType type;
        private String registrationNumber;
    }

    /**
     * 에스크로 결제를 사용하는 경우에 전달하는 상품별 정보다.
     */
    @Getter
    @Builder
    public static class EscrowProduct {
        private String id;
        private String name;
        private String code;
        private Long unitPrice;
        private Integer quantity;
    }
}
