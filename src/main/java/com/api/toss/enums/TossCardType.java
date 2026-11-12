package com.api.toss.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 토스 Payment 객체의 카드 종류다.
 */
public enum TossCardType {
    @JsonProperty("신용")
    CREDIT,
    @JsonProperty("체크")
    CHECK,
    @JsonProperty("기프트")
    GIFT,
    @JsonProperty("미확인")
    UNKNOWN;
}
