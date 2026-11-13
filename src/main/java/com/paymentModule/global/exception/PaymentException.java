package com.paymentModule.global.exception;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.paymentModule.data.enums.ResponseCode;
import com.paymentModule.domain.payment.processor.PaymentProcessResult;
import lombok.Getter;

import java.util.Objects;

/**
 * PG API 오류와 결제 결과를 함께 전달하는 업무 예외다.
 */
@Getter
public class PaymentException extends BusinessException {

    //DB 기록에 필요한 PG 세부 결과이며 오류 응답에는 객체 전체를 노출하지 않는다.
    @JsonIgnore
    private final PaymentProcessResult result;

    /**
     * PG 오류 메시지를 클라이언트 안내 문구로 사용한다.
     */
    public PaymentException(PaymentProcessResult result) {
        this(result, Objects.requireNonNull(result, "PG 오류 결과가 없습니다.").getMessage());
    }

    /**
     * PG 오류 정보는 유지하고 클라이언트 안내 문구만 바꾼다.
     */
    public PaymentException(PaymentProcessResult result, String message) {
        super(ResponseCode.PAYMENT_CONFLICT.getCode(), message);
        this.result = Objects.requireNonNull(result, "PG 오류 결과가 없습니다.");
    }

    /**
     * 예외 메시지를 기존 업무 예외의 메시지 필드와 일치시킨다.
     */
    @Override
    public String getMessage() {
        return getMsg();
    }
}
