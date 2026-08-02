package com.paymentModule.data.model.entity.user;

import com.paymentModule.data.enums.UserRoleType;
import com.paymentModule.data.model.entity.BaseEntity;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import lombok.*;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table
@Getter
@Builder
@AllArgsConstructor
public class User extends BaseEntity {

    @Id
    @Tsid
    @Column(nullable = false)
    private Long id;

    @Column(nullable = false, columnDefinition = "VARCHAR(20) NOT NULL COMMENT '사용자 ID'")
    private String userId;

    @Column(nullable = false, columnDefinition = "VARCHAR(255) NOT NULL COMMENT '패스워드'")
    private String password;

    @Column(nullable = false, columnDefinition = "VARCHAR(50) NOT NULL COMMENT '이메일'")
    private String email;

    @Column(nullable = false, columnDefinition = "VARCHAR(20) NOT NULL COMMENT '유저명'")
    private String userName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(10) NOT NULL COMMENT '유저 권한 USER, ADMIN'")
    private UserRoleType role;
}
