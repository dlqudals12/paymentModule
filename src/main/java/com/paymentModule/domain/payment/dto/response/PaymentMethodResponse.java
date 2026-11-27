package com.paymentModule.domain.payment.dto.response;

import com.paymentModule.data.model.entity.payment.PaymentMethod;
import lombok.Builder;
import lombok.Getter;

/**
 * 저장된 현재 결제수단의 카드·계좌·영수증 안내 정보를 반환한다.
 */
@Getter
@Builder
public class PaymentMethodResponse {
    private String cardIssuerCode;
    private String cardIssuerName;
    private String cardMaskedNumber;
    private Integer cardInstallmentMonths;
    private String cardApprovalNumber;
    private String transferBankCode;
    private String transferBankName;
    private String virtualAccountBankCode;
    private String virtualAccountBankName;
    private String virtualAccountNumber;
    private String virtualAccountDepositorName;
    private String virtualAccountDueDate;
    private String easyPayProvider;
    private String cashReceiptIssueNumber;
    private String cashReceiptUrl;

    /**
     * 현재 결제수단 정보가 없는 준비 상태에서는 null을 반환한다.
     */
    public static PaymentMethodResponse of(PaymentMethod paymentMethodDetails) {
        if (paymentMethodDetails == null) {
            return null;
        }

        return PaymentMethodResponse.builder()
                .cardIssuerCode(paymentMethodDetails.getCardIssuerCode())
                .cardIssuerName(paymentMethodDetails.getCardIssuerName())
                .cardMaskedNumber(paymentMethodDetails.getCardMaskedNumber())
                .cardInstallmentMonths(paymentMethodDetails.getCardInstallmentMonths())
                .cardApprovalNumber(paymentMethodDetails.getCardApprovalNumber())
                .transferBankCode(paymentMethodDetails.getTransferBankCode())
                .transferBankName(paymentMethodDetails.getTransferBankName())
                .virtualAccountBankCode(paymentMethodDetails.getVirtualAccountBankCode())
                .virtualAccountBankName(paymentMethodDetails.getVirtualAccountBankName())
                .virtualAccountNumber(paymentMethodDetails.getVirtualAccountNumber())
                .virtualAccountDepositorName(paymentMethodDetails.getVirtualAccountDepositorName())
                .virtualAccountDueDate(paymentMethodDetails.getVirtualAccountDueDate())
                .easyPayProvider(paymentMethodDetails.getEasyPayProvider())
                .cashReceiptIssueNumber(paymentMethodDetails.getCashReceiptIssueNumber())
                .cashReceiptUrl(paymentMethodDetails.getCashReceiptUrl())
                .build();
    }
}
