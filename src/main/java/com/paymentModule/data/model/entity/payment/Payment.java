package com.paymentModule.data.model.entity.payment;

import com.paymentModule.data.enums.PaymentMethod;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.enums.PaymentType;
import com.paymentModule.data.model.entity.BaseEntity;
import com.paymentModule.data.model.entity.user.User;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_idempotencyKey", columnNames = "idempotencyKey"),
        @UniqueConstraint(name = "uk_payment_provider_transactionId", columnNames = {"provider", "transactionId"})
})
@Comment("결제 현재 상태 및 결제 결과 관리")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Id
    @Tsid
    @Column(name = "id", nullable = false)
    @Comment("결제 PK")
    private Long id;

    @Column(name = "userId", nullable = false)
    @Comment("결제 대상 사용자 PK")
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;

    @Column(name = "loginId", nullable = false, length = 255)
    @Comment("결제 당시 사용자의 로그인 식별자")
    private String loginId;

    @Column(name = "productId", nullable = false)
    @Comment("결제 대상 상품 PK")
    private Long productId;

    @Column(name = "transactionId", length = 255)
    @Comment("외부 PG사에서 발급한 결제 식별자")
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30)
    @Comment("결제대행사 구분값")
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "paymentType", nullable = false, length = 30)
    @Comment("결제 유형")
    private PaymentType paymentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "paymentMethod", length = 30)
    @Comment("결제 수단")
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Comment("결제 상태")
    private PaymentStatus status;

    @Column(name = "amount", nullable = false)
    @Comment("최초 결제 요청 금액, 원 단위")
    private Long amount;

    @Builder.Default
    @Column(name = "cancelledAmount", nullable = false)
    @Comment("누적 취소 금액, 원 단위")
    private Long cancelledAmount = 0L;

    @Column(name = "idempotencyKey", length = 255)
    @Comment("동일한 결제 요청의 중복 처리를 방지하기 위한 요청 식별자")
    private String idempotencyKey;

    @Column(name = "failureCode", length = 100)
    @Comment("최근 결제 실패 또는 오류 코드")
    private String failureCode;

    @Column(name = "failureMessage", length = 500)
    @Comment("최근 결제 실패 또는 오류 메시지")
    private String failureMessage;

    @Column(name = "requestedAt")
    @Comment("PG사 결제 요청 일시")
    private LocalDateTime requestedAt;

    @Column(name = "approvedAt")
    @Comment("결제 승인 완료 일시")
    private LocalDateTime approvedAt;

    @Column(name = "cancelledAt")
    @Comment("결제 전액 취소 완료 일시")
    private LocalDateTime cancelledAt;
}
