package com.paymentModule.domain.user.service;

import com.paymentModule.config.security.jwt.JwtProvider;
import com.paymentModule.data.dto.user.CustomUserDetail;
import com.paymentModule.data.enums.JwtTokenType;
import com.paymentModule.data.model.entity.user.User;
import com.paymentModule.data.repository.user.UserRepository;
import com.paymentModule.domain.user.dto.request.UserLoginRequest;
import com.paymentModule.domain.user.dto.request.UserSaveRequest;
import com.paymentModule.global.exception.DataNotFountException;
import com.paymentModule.global.util.CookieUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.naming.AuthenticationException;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder encoder;

    /**
     * 회원 가입
     */
    @Transactional
    public void register(UserSaveRequest userSaveRequest) {
        //유저 id 중복 체크
        userRepository.findByUserId(userSaveRequest.getId()).ifPresent(
                user -> {
                    throw new DataNotFountException();
                }
        );

        //유저등록
        userRepository.save(User.builder()
                .userId(userSaveRequest.getId())
                .password(encoder.encode(userSaveRequest.getPassword()))
                .userName(userSaveRequest.getName())
                .email(userSaveRequest.getEmail())
                .role(userSaveRequest.getRole())
                .build());
    }

    /**
     * 로그인
     */
    public CustomUserDetail login(UserLoginRequest userLoginRequest, HttpServletResponse response) throws AuthenticationException {
        try {
            //유저 정보 조회
            User user = userRepository.findByUserId(userLoginRequest.getId()).orElseThrow(DataNotFountException::new);

            //비밀번호 체크
            if (!encoder.matches(userLoginRequest.getPassword(), user.getPassword())) {
                throw new AuthenticationException();
            }

            //userDetail 생성
            CustomUserDetail customUserDetail = new CustomUserDetail(user);

            //token 생성
            createTokenCookie(customUserDetail, response);

            return customUserDetail;
        } catch (Exception e) {
            //로그인시 로직에서의 오류는 어떤 오류인지 알지 못하게 하기 위해서 (알면 아이디 추측 가능)
            if (e instanceof AuthenticationException) {
                log.debug("PASSWORD ERROR -> id: {}", userLoginRequest.getId());
            }

            throw new AuthenticationException();
        }
    }

    /**
     * access token refresh
     */
    public void refresh(HttpServletRequest request, HttpServletResponse response) {
        //refresh token validation
        CustomUserDetail user = jwtProvider.validRefresh(request, response);

        //refresh token 생성 (refreshToken도 새로 생성)
        createTokenCookie(user, response);
    }

    /**
     * token 생성
     */
    private void createTokenCookie(CustomUserDetail customUserDetail, HttpServletResponse response) {
        JwtProvider.JwtTokenDto access = jwtProvider.generate(customUserDetail, JwtTokenType.ACCESS);
        JwtProvider.JwtTokenDto refresh = jwtProvider.generate(customUserDetail, JwtTokenType.REFRESH);

        CookieUtils.createTokenCookies(access, response);
        CookieUtils.createTokenCookies(refresh, response);
    }
}
