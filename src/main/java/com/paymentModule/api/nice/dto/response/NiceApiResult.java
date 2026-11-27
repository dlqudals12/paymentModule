package com.paymentModule.api.nice.dto.response;

/**
 * 나이스페이 HTTP 응답과 통신 실패를 처리기에 동일한 형태로 전달한다.
 */
public record NiceApiResult(Integer httpStatus, NiceApiResponse data, String code, String message) {
    /**
     * JSON 본문을 받은 정상 HTTP 응답이다.
     */
    public static NiceApiResult success(int httpStatus, NiceApiResponse data) {
        return new NiceApiResult(httpStatus, data, null, null);
    }

    /**
     * HTTP 오류 또는 응답을 받지 못한 통신 실패다.
     */
    public static NiceApiResult failure(Integer httpStatus, String code, String message) {
        return new NiceApiResult(httpStatus, null, code, message);
    }
}
