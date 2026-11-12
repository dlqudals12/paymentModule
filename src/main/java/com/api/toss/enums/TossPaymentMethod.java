package com.api.toss.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 토스 Payment 객체의 결제수단다.
 */
public enum TossPaymentMethod {
    @JsonProperty("카드")
    CARD,
    @JsonProperty("가상계좌")
    VIRTUAL_ACCOUNT,
    @JsonProperty("간편결제")
    EASY_PAY,
    @JsonProperty("휴대폰")
    MOBILE_PHONE,
    @JsonProperty("계좌이체")
    TRANSFER,
    @JsonProperty("문화상품권")
    CULTURE_GIFT_CERTIFICATE,
    @JsonProperty("도서문화상품권")
    BOOK_CULTURE_GIFT_CERTIFICATE,
    @JsonProperty("게임문화상품권")
    GAME_CULTURE_GIFT_CERTIFICATE;
}
