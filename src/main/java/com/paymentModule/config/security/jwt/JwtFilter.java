package com.paymentModule.config.security.jwt;

import com.paymentModule.data.enums.JwtTokenType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    //필터 제외 url
    public String[] excludeURI = {

    };

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String token = jwtProvider.getToken(request, JwtTokenType.ACCESS);

        //token validation
        if (StringUtils.hasText(token) && jwtProvider.validAccess(token)) {
            //validation 통과하면 context에 authentication 정보 저장
            Authentication authentication = jwtProvider.getAuthentication(token);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 필터 제외 url 체크
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        return List.of(excludeURI).contains(request.getRequestURI());
    }
}
