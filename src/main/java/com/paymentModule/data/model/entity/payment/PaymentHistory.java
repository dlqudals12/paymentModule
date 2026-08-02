package com.paymentModule.data.model.entity.payment;

import com.paymentModule.data.enums.PaymentHistoryType;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.model.entity.CreatedBaseEntity;
import com.paymentModule.data.model.entity.user.User;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "payment_history")
@Comment("결제 승인, 실패, 취소 및 상태 변경 이력")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentHistory extends CreatedBaseEntity {

    @Id
    @Tsid
    @Column(name = "id", nullable = false)
    @Comment("결제 이력 PK")
    private Long id;

    @Column(name = "paymentId", nullable = false)
    @Comment("결제 PK 참조값이며 DB 외래키 제약은 사용하지 않음")
    private Long paymentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paymentId", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Payment payment;

    @Column(name = "userId", nullable = false)
    @Comment("결제 대상 사용자 PK")
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;

    @Column(name = "loginId", nullable = false, length = 255)
    @Comment("이력 생성 당시 사용자의 로그인 식별자")
    private String loginId;

    @Enumerated(EnumType.STRING)
    @Column(name = "historyType", nullable = false, length = 30)
    @Comment("이력 유형")
    private PaymentHistoryType historyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "previousStatus", length = 30)
    @Comment("처리 전 결제 상태")
    private PaymentStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "currentStatus", nullable = false, length = 30)
    @Comment("처리 후 결제 상태")
    private PaymentStatus currentStatus;

    @Column(name = "transactionAmount")
    @Comment("해당 이력에서 처리한 승인 또는 취소 금액, 원 단위")
    private Long transactionAmount;

    @Column(name = "cumulativeCancelledAmount")
    @Comment("해당 이력 발생 시점의 누적 취소 금액, 원 단위")
    private Long cumulativeCancelledAmount;

    @Column(name = "providerTransactionId", length = 255)
    @Comment("PG사에서 발급한 승인 또는 취소 거래 식별자")
    private String providerTransactionId;

    @Column(name = "resultCode", length = 100)
    @Comment("PG사 처리 결과 코드")
    private String resultCode;

    @Column(name = "resultMessage", length = 500)
    @Comment("PG사 처리 결과 메시지")
    private String resultMessage;
}
