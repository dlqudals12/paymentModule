package com.api.toss.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 토스 Payment 객체의 가상계좌 유형다.
 */
public enum TossVirtualAccountType {
    @JsonProperty("일반")
    GENERAL,
    @JsonProperty("고정")
    FIXED;
}
