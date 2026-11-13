package com.paymentModule.data.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass
public abstract class BaseEntity extends CreatedBaseEntity {

    @Column(name = "updatedBy", nullable = false, length = 255, comment = "수정한 사용자 로그인 ID")
    private String updatedBy;

    @Column(name = "updatedAt", nullable = false, comment = "수정 일시")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void prePersistUpdated() {
        updatedBy = currentLoginId();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void preUpdate() {
        updatedBy = currentLoginId();
        updatedAt = LocalDateTime.now();
    }
}
