package com.paymentModule.data.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass
public abstract class CreatedBaseEntity {

    private static final String SYSTEM_USER = "system";

    @Column(name = "createdBy", nullable = false, length = 255, updatable = false, comment = "등록한 사용자 로그인 ID")
    private String createdBy;

    @Column(name = "createdAt", nullable = false, updatable = false, comment = "등록 일시")
    private LocalDateTime createdAt;

    @PrePersist
    protected void prePersistCreated() {
        createdBy = currentLoginId();
        createdAt = LocalDateTime.now();
    }

    protected String currentLoginId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !StringUtils.hasText(authentication.getName())
                || "anonymousUser".equals(authentication.getName())) {
            return SYSTEM_USER;
        }
        return authentication.getName();
    }
}
