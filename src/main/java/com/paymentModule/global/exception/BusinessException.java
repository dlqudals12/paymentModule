package com.paymentModule.global.exception;

import com.paymentModule.data.enums.ResponseCode;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private int code;
    private String msg;

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(ResponseCode responseCode) {
        this.code = responseCode.getCode();
        this.msg = responseCode.getMsg();
    }

    public BusinessException(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
