package com.paymentModule.api.toss.enums;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.paymentModule.data.enums.PaymentMethod;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 토스 Payment 객체의 결제수단다.
 */
@Getter
@RequiredArgsConstructor
public enum TossPaymentMethod {
    @JsonProperty("카드")
    CARD(PaymentMethod.CARD),
    @JsonProperty("가상계좌")
    VIRTUAL_ACCOUNT(PaymentMethod.VIRTUAL_ACCOUNT),
    @JsonProperty("간편결제")
    EASY_PAY(null),
    @JsonProperty("휴대폰")
    MOBILE_PHONE(null),
    @JsonProperty("계좌이체")
    TRANSFER(PaymentMethod.TRANSFER),
    @JsonProperty("문화상품권")
    CULTURE_GIFT_CERTIFICATE(null),
    @JsonProperty("도서문화상품권")
    BOOK_CULTURE_GIFT_CERTIFICATE(null),
    @JsonProperty("게임문화상품권")
    GAME_CULTURE_GIFT_CERTIFICATE(null);

    /** 공통 결제수단이 아직 없는 토스 수단은 null이다. */
    private final PaymentMethod paymentMethod;
}
