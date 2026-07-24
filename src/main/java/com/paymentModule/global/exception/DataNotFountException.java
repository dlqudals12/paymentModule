package com.paymentModule.global.exception;

import com.paymentModule.data.enums.ResponseCode;

public class DataNotFountException extends BusinessException {

    public DataNotFountException() {
        super(ResponseCode.DATA_NOT_FOUND);
    }

    public DataNotFountException(String msg) {
        super(ResponseCode.DATA_NOT_FOUND.getCode(), msg);
    }
}
