package com.paymentModule.global.constant;

public class SecurityConstants {

    /**
     * new 생성 막음
     */
    private SecurityConstants() {
    }

    /**
     * CORS 설정
     */
    public static final String[] ALLOW_ORIGINS = {
            "http://localhost:3000"
    };

    /**
     *
     */
    public static final String[] EXCLUDE_URLS = {
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/api/auth/**"
    };
}
