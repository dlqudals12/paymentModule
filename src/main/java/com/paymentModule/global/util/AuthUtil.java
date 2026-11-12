package com.paymentModule.global.util;

import com.paymentModule.data.dto.user.CustomUserDetail;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class AuthUtil {

    /**
     * context에 저장된 유저 정보 조회
     */
    public static CustomUserDetail authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetail user)) {
            throw new AuthenticationCredentialsNotFoundException("인증된 사용자를 찾을 수 없습니다.");
        }

        return user;
    }
}
