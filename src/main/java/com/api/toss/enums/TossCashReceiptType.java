package com.api.toss.enums;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 토스 Payment 객체의 현금영수증 용도다.
 */
public enum TossCashReceiptType {
    @JsonProperty("소득공제")
    INCOME_DEDUCTION,
    @JsonProperty("지출증빙")
    EXPENSE_PROOF,
    @JsonProperty("미발행")
    NOT_ISSUED;
}
