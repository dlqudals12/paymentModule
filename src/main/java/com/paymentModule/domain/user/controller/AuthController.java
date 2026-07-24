package com.paymentModule.domain.user.controller;

import com.paymentModule.data.dto.response.SuccessResponse;
import com.paymentModule.data.dto.user.CustomUserDetail;
import com.paymentModule.domain.user.dto.request.UserLoginRequest;
import com.paymentModule.domain.user.dto.request.UserSaveRequest;
import com.paymentModule.domain.user.service.UserService;
import com.paymentModule.global.util.CookieUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.naming.AuthenticationException;

@Tag(name = "Auth", description = "Auth")
@RestController
@RequiredArgsConstructor
@RequestMapping("/apis/v3/auth")
public class AuthController {

    private final UserService userService;

    @Operation(summary = "회원 가입", description = "회원 가입")
    @ApiResponse(responseCode = "200", description = "OK")
    @PostMapping("/register")
    public SuccessResponse<Object> register(@RequestBody UserSaveRequest userSaveRequest) {
        userService.register(userSaveRequest);
        return SuccessResponse.ok();
    }

    @Operation(summary = "로그인", description = "로그인")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = CustomUserDetail.class)))
    @PostMapping("/login")
    public SuccessResponse<CustomUserDetail> login(
            @RequestBody UserLoginRequest userLoginRequest,
            HttpServletResponse response) throws AuthenticationException {
        return SuccessResponse.of(userService.login(userLoginRequest, response));
    }

    @Operation(summary = "토큰 리브레시", description = "토큰 리프레시")
    @PostMapping("/refresh")
    public SuccessResponse<Object> refresh(HttpServletRequest request, HttpServletResponse response) {
        userService.refresh(request, response);
        return SuccessResponse.ok();
    }

    @Operation(summary = "로그아웃", description = "로그아웃")
    @PostMapping("/logout")
    public SuccessResponse<Object> logout(HttpServletResponse response) {
        CookieUtils.deleteTokenCookies(response);
        return SuccessResponse.ok();
    }

}
