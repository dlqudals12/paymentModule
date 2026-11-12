package com.api.toss.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * 토스 API v1 오류 본문의 code와 message를 받는다.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TossApiErrorResponse {
    private String code;
    private String message;
}
