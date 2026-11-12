package com.api.toss.dto.response;

import lombok.Getter;

/**
 * 토스 API 호출의 HTTP 결과와 Payment 응답을 함께 전달한다.
 * 토스 HTTP 응답이 없는 실패에는 API 클라이언트가 정한 상태 코드를 담는다.
 */
@Getter
public class TossApiResult<T> {
    private final int httpStatus;
    private final String code;
    private final String message;
    private final T data;

    private TossApiResult(int httpStatus, String code, String message, T data) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 정상 응답의 상태 코드와 본문을 보관한다.
     */
    public static <T> TossApiResult<T> success(int httpStatus, T data) {
        return new TossApiResult<>(httpStatus, "SUCCESS", "토스 API 요청 성공", data);
    }

    /**
     * 토스 오류 또는 통신 오류를 보관하고 데이터는 null로 둔다.
     */
    public static <T> TossApiResult<T> failure(int httpStatus, String code, String message) {
        return new TossApiResult<>(httpStatus, code, message, null);
    }
}
