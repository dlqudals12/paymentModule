package com.paymentModule.global.exception;

import com.paymentModule.data.dto.response.CommonResponse;
import com.paymentModule.data.enums.ResponseCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PG API 오류를 결제 API의 공통 오류 응답으로 변환한다.
 */
@RestControllerAdvice
public class PaymentExceptionHandler {

    /**
     * PG 오류 코드와 안내 문구를 담아 결제 충돌 응답을 반환한다.
     */
    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<CommonResponse> payment(PaymentException exception) {
        //PG 코드가 없으면 모듈의 409 응답 코드를 사용해 오류 구분값을 비우지 않는다.
        String providerCode = exception.getResult().getCode();
        String code = providerCode == null || providerCode.isBlank()
                ? ResponseCode.PAYMENT_CONFLICT.name() : providerCode;

        //외부 오류 메시지가 비어 있어도 클라이언트에는 기본 안내 문구를 제공한다.
        String message = exception.getMsg() == null || exception.getMsg().isBlank()
                ? ResponseCode.PAYMENT_CONFLICT.getMsg() : exception.getMsg();

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new CommonResponse(ResponseCode.PAYMENT_CONFLICT.getCode(), code, message));
    }
}
