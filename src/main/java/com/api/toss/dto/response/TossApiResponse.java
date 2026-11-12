package com.api.toss.dto.response;

import com.api.toss.enums.TossCardAcquireStatus;
import com.api.toss.enums.TossCardOwnerType;
import com.api.toss.enums.TossCardType;
import com.api.toss.enums.TossCashReceiptIssueStatus;
import com.api.toss.enums.TossCashReceiptTransactionType;
import com.api.toss.enums.TossCashReceiptType;
import com.api.toss.enums.TossInterestPayer;
import com.api.toss.enums.TossPaymentMethod;
import com.api.toss.enums.TossPaymentStatus;
import com.api.toss.enums.TossPaymentType;
import com.api.toss.enums.TossRefundStatus;
import com.api.toss.enums.TossSettlementStatus;
import com.api.toss.enums.TossVirtualAccountType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 토스페이먼츠 Payment 객체의 응답 모델이다.
 * 결제 수단과 상태에 따라 일부 중첩 객체는 null로 반환된다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TossApiResponse {
    // 결제 식별 정보와 현재 상태
    private String version;
    private String paymentKey;
    private TossPaymentType type;
    private String orderId;
    private String orderName;
    private String mId;
    private String currency;
    private TossPaymentMethod method;
    private Long totalAmount;
    private Long balanceAmount;
    private TossPaymentStatus status;
    private String requestedAt;
    private String approvedAt;

    // 에스크로, 거래 키, 세금 정보
    private Boolean useEscrow;
    private String escrowKey;
    private String lastTransactionKey;
    private String transactionKey;
    private Long suppliedAmount;
    private Long vat;
    private Boolean cultureExpense;
    private Long taxFreeAmount;
    private Long taxExemptionAmount;
    private List<Cancel> cancels;
    private Boolean isPartialCancelable;

    // 결제 수단별 상세 정보
    private Card card;
    private VirtualAccount virtualAccount;
    private String secret;
    private MobilePhone mobilePhone;
    private GiftCertificate giftCertificate;
    private Transfer transfer;
    private Map<String, String> metadata;
    private UrlInfo receipt;
    private UrlInfo checkout;
    private EasyPay easyPay;
    private Long easyPayAmount;
    private Long easyPayDiscountAmount;
    private String country;
    private Failure failure;
    private CashReceipt cashReceipt;
    private List<CashReceiptHistory> cashReceipts;
    private Discount discount;

    /**
     * 전액 또는 부분 취소 한 건의 금액, 상태, 거래 식별 정보다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Cancel {
        private Long cancelAmount;
        private String cancelReason;
        private Long taxFreeAmount;
        private Long taxExemptionAmount;
        private Long refundableAmount;
        private Long cardDiscountAmount;
        private Long transferDiscountAmount;
        private Long easyPayDiscountAmount;
        private String canceledAt;
        private String transactionKey;
        private String receiptKey;
        private String cancelStatus;
        private String cancelRequestId;
    }

    /**
     * 카드 결제의 카드사, 승인, 할부 및 매입 정보다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Card {
        private Long amount;
        private String issuerCode;
        private String acquirerCode;
        private String number;
        private Integer installmentPlanMonths;
        private String approveNo;
        private Boolean useCardPoint;
        private TossCardType cardType;
        private TossCardOwnerType ownerType;
        private TossCardAcquireStatus acquireStatus;
        private Boolean isInterestFree;
        private TossInterestPayer interestPayer;
    }

    /**
     * 가상계좌 발급 결과와 입금·환불 상태다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VirtualAccount {
        private TossVirtualAccountType accountType;
        private String accountNumber;
        private String bankCode;
        private String customerName;
        private String depositorName;
        private String dueDate;
        private TossRefundStatus refundStatus;
        private Boolean expired;
        private TossSettlementStatus settlementStatus;
        private RefundReceiveAccount refundReceiveAccount;
    }

    /**
     * 가상계좌 결제 취소 시 사용할 환불 계좌다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RefundReceiveAccount {
        private String bankCode;
        private String accountNumber;
        private String holderName;
    }

    /**
     * 휴대폰 결제의 구매자 번호와 정산·영수증 정보다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MobilePhone {
        private String customerMobilePhone;
        private TossSettlementStatus settlementStatus;
        private String receiptUrl;
    }

    /**
     * 상품권 결제의 승인번호와 정산 상태다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GiftCertificate {
        private String approveNo;
        private TossSettlementStatus settlementStatus;
    }

    /**
     * 계좌이체에 사용한 은행과 정산 상태다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Transfer {
        private String bankCode;
        private TossSettlementStatus settlementStatus;
    }

    /**
     * 영수증과 결제창 객체가 공통으로 가지는 URL이다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UrlInfo {
        private String url;
    }

    /**
     * 간편결제 제공사와 결제·할인 금액이다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EasyPay {
        private String provider;
        private Long amount;
        private Long discountAmount;
    }

    /**
     * 결제 실패 또는 현금영수증 처리 실패의 코드와 메시지다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Failure {
        private String code;
        private String message;
    }

    /**
     * 현재 결제에 연결된 현금영수증 정보다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CashReceipt {
        private TossCashReceiptType type;
        private String receiptKey;
        private String issueNumber;
        private String receiptUrl;
        private Long amount;
        private Long taxFreeAmount;
    }

    /**
     * 현금영수증 발급과 취소 각각의 이력이다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CashReceiptHistory {
        private String receiptKey;
        private String orderId;
        private String orderName;
        private TossCashReceiptType type;
        private String issueNumber;
        private String receiptUrl;
        private String businessNumber;
        private TossCashReceiptTransactionType transactionType;
        private Long amount;
        private Long taxFreeAmount;
        private TossCashReceiptIssueStatus issueStatus;
        private Failure failure;
        private String customerIdentityNumber;
        private String requestedAt;
    }

    /**
     * 카드사 또는 계좌이체 즉시 할인 금액이다.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Discount {
        private Long amount;
    }
}
