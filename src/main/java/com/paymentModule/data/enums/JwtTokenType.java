package com.paymentModule.data.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum JwtTokenType {
    ACCESS("access-token"),
    REFRESH("refresh-token");

    private final String value;
}
