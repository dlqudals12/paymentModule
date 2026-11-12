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
        @UniqueConstraint(name = "uk_payment_provider_transactionId", columnNames = {"provider", "transactionId"}),
        @UniqueConstraint(name = "uk_payment_provider_activeOrderId", columnNames = {"provider", "activeOrderId"})
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

    @Column(name = "orderId", nullable = false, length = 64)
    @Comment("결제 요청과 승인에 사용하는 주문 식별자")
    private String orderId;

    @Column(name = "activeOrderId", length = 64)
    @Comment("진행 가능한 결제 시도의 주문 식별자. 실패한 시도는 비워 새 시도를 허용한다.")
    private String activeOrderId;

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
    @Comment("결제 준비 단계에 저장한 금액, 원 단위")
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

    /**
     * SDK 실행 전 같은 주문을 다시 준비하면 서버에서 확인한 금액으로 갱신한다.
     */
    public void updateReadyAmount(Long amount) {
        this.amount = amount;
    }

    /**
     * 승인 요청을 시작했음을 기록한다. 네트워크 호출 전 별도 트랜잭션에서 확정한다.
     */
    public void process() {
        this.status = PaymentStatus.PROCESSING;
        this.requestedAt = LocalDateTime.now();
    }

    /**
     * 토스 승인 결과와 결제 식별자를 저장한다.
     */
    public void approve(String transactionId, PaymentMethod paymentMethod, LocalDateTime approvedAt) {
        this.status = PaymentStatus.SUCCESS;
        this.transactionId = transactionId;
        this.paymentMethod = paymentMethod;
        this.approvedAt = approvedAt;
        this.failureCode = null;
        this.failureMessage = null;
    }

    /**
     * 가상계좌 입금 대기처럼 인증은 되었지만 결제가 아직 완료되지 않은 상태를 기록한다.
     */
    public void pending(String transactionId, PaymentMethod paymentMethod) {
        this.status = PaymentStatus.PROCESSING;
        this.transactionId = transactionId;
        this.paymentMethod = paymentMethod;
    }

    /**
     * 승인 실패 또는 결과 미확인 상태를 저장한다. 통신 오류는 실패로 단정하지 않는다.
     */
    public void fail(PaymentStatus status, String code, String message) {
        this.status = status;
        this.failureCode = code;
        this.failureMessage = message;

        if (status == PaymentStatus.FAILED) {
            this.activeOrderId = null;
        }
    }
}
