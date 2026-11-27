package com.paymentModule.api.nice.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * 나이스페이 승인·거래 조회 JSON에서 결제 처리에 사용하는 값이다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class NiceApiResponse {
    private String resultCode;
    private String resultMsg;
    private String tid;
    private String orderId;
    private String status;
    private String payMethod;
    private Long amount;
    private String paidAt;
    private String receiptUrl;
    private String approveNo;
    private Bank bank;
    private Card card;
    private VirtualAccount vbank;

    /**
     * 카드 결제에서 실제 응답에 포함되는 카드사와 승인 정보를 보관한다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Card {
        private String cardCode;
        private String cardName;
        private String cardNum;
        private Integer cardQuota;
        private Boolean isInterestFree;
        private String cardType;
        private String acquCardCode;
        private String acquCardName;
    }

    /**
     * 계좌이체에 사용된 은행 정보다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bank {
        private String bankCode;
        private String bankName;
    }

    /**
     * 가상계좌 발급 결과의 입금 안내 정보다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VirtualAccount {
        private String vbankCode;
        private String vbankName;
        private String vbankNumber;
        private String vbankExpDate;
        private String vbankHolder;
    }
}
