package com.paymentModule.data.dto.response;

import com.paymentModule.data.enums.ResponseCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SuccessResponse<T> extends CommonResponse{

    private T data;

    public SuccessResponse(Builder<T> builder) {
        super(builder.status, builder.code, builder.message);
        this.data = builder.data;
    }

    public static <T> SuccessResponse<T> of(T data) {
        return new Builder<T>().success().data(data).build();
    }

    public static SuccessResponse<Object> ok() {
        return SuccessResponse.builder().success().build();
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private int status;
        private String code;
        private String message;
        private T data;

        public Builder<T> success() {
            this.status = ResponseCode.OK.getCode();
            this.code = ResponseCode.OK.name();
            this.message = ResponseCode.OK.getMsg();
            return this;
        }

        public Builder<T> data(T data) {
            this.data = data;
            return this;
        }

        public SuccessResponse<T> build() {
            return new SuccessResponse<T>(this);
        }
    }
}
