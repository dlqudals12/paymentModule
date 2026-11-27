package com.paymentModule.data.model.entity.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 현재 결제수단을 다시 보여줄 때 필요한 정보만 payment 행의 nullable 컬럼으로 묶는다.
 */
@Getter
@Builder
@Embeddable
@AllArgsConstructor
@NoArgsConstructor
public class PaymentMethod {
    @Column(name = "cardIssuerCode", length = 30)
    private String cardIssuerCode;

    @Column(name = "cardIssuerName", length = 100)
    private String cardIssuerName;

    @Column(name = "cardMaskedNumber", length = 30)
    private String cardMaskedNumber;

    @Column(name = "cardInstallmentMonths")
    private Integer cardInstallmentMonths;

    @Column(name = "cardApprovalNumber", length = 50)
    private String cardApprovalNumber;

    @Column(name = "transferBankCode", length = 30)
    private String transferBankCode;

    @Column(name = "transferBankName", length = 100)
    private String transferBankName;

    @Column(name = "virtualAccountBankCode", length = 30)
    private String virtualAccountBankCode;

    @Column(name = "virtualAccountBankName", length = 100)
    private String virtualAccountBankName;

    @Column(name = "virtualAccountNumber", length = 100)
    private String virtualAccountNumber;

    @Column(name = "virtualAccountDepositorName", length = 100)
    private String virtualAccountDepositorName;

    @Column(name = "virtualAccountDueDate", length = 50)
    private String virtualAccountDueDate;

    @Column(name = "easyPayProvider", length = 100)
    private String easyPayProvider;

    @Column(name = "cashReceiptIssueNumber", length = 100)
    private String cashReceiptIssueNumber;

    @Column(name = "cashReceiptUrl", length = 1000)
    private String cashReceiptUrl;

    /**
     * 후속 조회에서 누락된 필드는 기존 값을 유지하고 새로 받은 값만 반영한다.
     */
    public void update(PaymentMethod incoming) {
        if (incoming.cardIssuerCode != null) {
            cardIssuerCode = incoming.cardIssuerCode;
        }
        if (incoming.cardIssuerName != null) {
            cardIssuerName = incoming.cardIssuerName;
        }
        if (incoming.cardMaskedNumber != null) {
            cardMaskedNumber = incoming.cardMaskedNumber;
        }
        if (incoming.cardInstallmentMonths != null) {
            cardInstallmentMonths = incoming.cardInstallmentMonths;
        }
        if (incoming.cardApprovalNumber != null) {
            cardApprovalNumber = incoming.cardApprovalNumber;
        }

        if (incoming.transferBankCode != null) {
            transferBankCode = incoming.transferBankCode;
        }
        if (incoming.transferBankName != null) {
            transferBankName = incoming.transferBankName;
        }

        if (incoming.virtualAccountBankCode != null) {
            virtualAccountBankCode = incoming.virtualAccountBankCode;
        }
        if (incoming.virtualAccountBankName != null) {
            virtualAccountBankName = incoming.virtualAccountBankName;
        }
        if (incoming.virtualAccountNumber != null) {
            virtualAccountNumber = incoming.virtualAccountNumber;
        }
        if (incoming.virtualAccountDepositorName != null) {
            virtualAccountDepositorName = incoming.virtualAccountDepositorName;
        }
        if (incoming.virtualAccountDueDate != null) {
            virtualAccountDueDate = incoming.virtualAccountDueDate;
        }

        if (incoming.easyPayProvider != null) {
            easyPayProvider = incoming.easyPayProvider;
        }

        if (incoming.cashReceiptIssueNumber != null) {
            cashReceiptIssueNumber = incoming.cashReceiptIssueNumber;
        }
        if (incoming.cashReceiptUrl != null) {
            cashReceiptUrl = incoming.cashReceiptUrl;
        }
    }
}
