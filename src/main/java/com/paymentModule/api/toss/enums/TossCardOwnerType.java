package com.paymentModule.api.toss.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 토스 Payment 객체의 카드 소유자 유형다.
 */
public enum TossCardOwnerType {
    @JsonProperty("개인")
    PERSONAL,
    @JsonProperty("법인")
    CORPORATE,
    @JsonProperty("미확인")
    UNKNOWN;
}
