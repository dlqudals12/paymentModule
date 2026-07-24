package com.paymentModule.config.security.jwt;

import com.paymentModule.data.dto.user.CustomUserDetail;
import com.paymentModule.data.enums.JwtTokenType;
import com.paymentModule.global.properties.JwtProperties;
import com.paymentModule.global.util.CookieUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtProvider {

    private final SecretKey SECRET_KEY;
    private final JwtProperties.JwtToken jwtTokenProperties;

    public record JwtTokenDto(String token, JwtTokenType tokenType, long expire) {
    }

    public JwtProvider(JwtProperties jwtProperties) {
        this.jwtTokenProperties = jwtProperties.getToken();

        //secret key 생성
        this.SECRET_KEY = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecretKey()));
    }

    /**
     * jwt 토큰 생성
     */
    public JwtTokenDto generate(CustomUserDetail user, JwtTokenType tokenType) {
        //현재 시간
        long now = System.currentTimeMillis();

        //토큰 타입마다 만료시간
        long expire = tokenType == JwtTokenType.ACCESS
                ? jwtTokenProperties.access() : jwtTokenProperties.refresh();

        //token 생성
        String token = Jwts.builder()
                .claims(createClaims(user))
                .subject(user.getUserId())
                .issuedAt(new Date())
                .expiration(new Date(now + expire))
                .signWith(SECRET_KEY)
                .compact();

        //token return
        return new JwtTokenDto(token, tokenType, expire);
    }

    /**
     * request 에서 token 추출
     */
    public String getToken(HttpServletRequest request, JwtTokenType jwtTokenType) {
        Cookie[] cookies = request.getCookies();

        if (cookies == null) return null;

        return Arrays.stream(cookies)
                .filter(cookie -> cookie.getName().equals(jwtTokenType.getValue()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse("");
    }

    /**
     * access token validation
     */
    public boolean validAccess(String token) {
        boolean isValid;
        try {
            Claims claims = getClaims(token);

            isValid = claims != null;

            //redis 추가시 token 맞지 않으면 오류 후 로그아웃
        } catch (Exception e) {
            isValid = false;
        }

        return isValid;
    }

    /**
     * access token refresh
     */
    public CustomUserDetail validRefresh(HttpServletRequest request, HttpServletResponse response) {
        boolean isValid;
        CustomUserDetail customUserDetail = null;

        try {
            String token = getToken(request, JwtTokenType.REFRESH);
            Claims claims = getClaims(token);

            isValid = claims != null;

            customUserDetail = new CustomUserDetail(claims);
        } catch (Exception e) {
            isValid = false;
        }

        if (!isValid) {
            CookieUtils.deleteTokenCookies(response);

            throw new UnsupportedJwtException("Expired token");
        }

        return customUserDetail;
    }

    /**
     * token 정보 조회
     */
    public Authentication getAuthentication(String token) {
        Claims claims = getClaims(token);
        CustomUserDetail user = new CustomUserDetail(claims);
        return new UsernamePasswordAuthenticationToken(user, "", user.getAuthorities());
    }

    /**
     * 토큰 정보 추출
     */
    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(SECRET_KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * claim 생성
     */
    private Map<String, Object> createClaims(CustomUserDetail user) {
        HashMap<String, Object> claims = new HashMap<>();
        String roles = String.join(",", user.getRoles().stream().map(Enum::toString).toList());
        claims.put("id", user.getId());
        claims.put("userId", user.getUserId());
        claims.put("email", user.getEmail());
        claims.put("name", user.getName());
        claims.put("roles", roles);
        return claims;
    }
}
