package com.paymentModule.data.model.entity.payment;

import com.paymentModule.data.enums.PaymentMethod;
import com.paymentModule.data.enums.OrderStatus;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.enums.PaymentType;
import com.paymentModule.data.model.entity.BaseEntity;
import com.paymentModule.data.model.entity.user.User;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_idempotencyKey", columnNames = "idempotencyKey"),
        @UniqueConstraint(name = "uk_payment_provider_transactionId", columnNames = {"provider", "transactionId"}),
        @UniqueConstraint(name = "uk_payment_provider_activeOrderId", columnNames = {"provider", "activeOrderId"})
}, comment = "결제 현재 상태 및 결제 결과 관리")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Id
    @Tsid
    @Column(name = "id", nullable = false, comment = "결제 PK")
    private Long id;

    @Column(name = "userId", nullable = false, comment = "결제 대상 사용자 PK")
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;

    @Column(name = "loginId", nullable = false, length = 255, comment = "결제 당시 사용자의 로그인 식별자")
    private String loginId;

    @Column(name = "orderId", nullable = false, length = 64, comment = "결제 요청과 승인에 사용하는 주문 식별자")
    private String orderId;

    @Column(name = "activeOrderId", length = 64, comment = "진행 가능한 결제 시도의 주문 식별자. 실패한 시도는 비워 새 시도를 허용한다.")
    private String activeOrderId;

    @Column(name = "transactionId", length = 255, comment = "외부 PG사에서 발급한 결제 식별자")
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30, comment = "결제대행사 구분값")
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "paymentType", nullable = false, length = 30, comment = "결제 유형")
    private PaymentType paymentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "paymentMethod", length = 30, comment = "결제 수단")
    private PaymentMethod paymentMethod;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "paymentStatus", nullable = false, length = 30, comment = "결제 상태")
    private PaymentStatus paymentStatus = PaymentStatus.BEFORE;

    @Builder.Default
    @Column(name = "paymentStatusChangedAt", nullable = false, comment = "결제 상태가 마지막으로 변경된 일시")
    private LocalDateTime paymentStatusChangedAt = LocalDateTime.now();

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "orderStatus", nullable = false, length = 30, comment = "결제 성공 이후 주문 반영 상태: BEFORE, PENDING, COMPLETE")
    private OrderStatus orderStatus = OrderStatus.BEFORE;

    @Builder.Default
    @Column(name = "orderStatusChangedAt", nullable = false, comment = "주문 반영 상태가 마지막으로 변경된 일시")
    private LocalDateTime orderStatusChangedAt = LocalDateTime.now();

    @Column(name = "amount", nullable = false, comment = "결제 준비 단계에 저장한 금액, 원 단위")
    private Long amount;

    @Builder.Default
    @Column(name = "cancelledAmount", nullable = false, comment = "누적 취소 금액, 원 단위")
    private Long cancelledAmount = 0L;

    @Column(name = "idempotencyKey", length = 255, comment = "동일한 결제 요청의 중복 처리를 방지하기 위한 요청 식별자")
    private String idempotencyKey;

    @Column(name = "failureCode", length = 100, comment = "최근 결제 실패 또는 오류 코드")
    private String failureCode;

    @Column(name = "failureMessage", length = 500, comment = "최근 결제 실패 또는 오류 메시지")
    private String failureMessage;

    @Column(name = "requestedAt", comment = "PG사 결제 요청 일시")
    private LocalDateTime requestedAt;

    @Column(name = "approvedAt", comment = "결제 승인 완료 일시")
    private LocalDateTime approvedAt;

    @Column(name = "cancelledAt", comment = "결제 전액 취소 완료 일시")
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
    public void process(String transactionId) {
        updatePaymentStatus(PaymentStatus.PROCESSING);
        //SDK에서 받은 PG 식별자를 먼저 저장해 다른 탭의 결제 키와 구별한다.
        this.transactionId = transactionId;
        this.requestedAt = LocalDateTime.now();
    }

    /**
     * 토스 승인 결과와 결제 식별자를 저장한다.
     */
    public void approve(String transactionId, PaymentMethod paymentMethod, LocalDateTime approvedAt) {
        updatePaymentStatus(PaymentStatus.SUCCESS);
        this.transactionId = transactionId;
        this.paymentMethod = paymentMethod;
        //PG 승인 시각이 없으면 서버에서 성공 결과를 확정한 시각을 사용한다.
        this.approvedAt = approvedAt == null ? LocalDateTime.now() : approvedAt;
        this.failureCode = null;
        this.failureMessage = null;
    }

    /**
     * 가상계좌 입금 대기처럼 인증은 되었지만 결제가 아직 완료되지 않은 상태를 기록한다.
     */
    public void pending(String transactionId, PaymentMethod paymentMethod) {
        updatePaymentStatus(PaymentStatus.PROCESSING);
        this.transactionId = transactionId;
        this.paymentMethod = paymentMethod;
    }

    /**
     * 승인 실패 또는 결과 미확인 상태를 저장한다. 통신 오류는 실패로 단정하지 않는다.
     */
    public void fail(PaymentStatus status, String code, String message) {
        updatePaymentStatus(status);
        this.failureCode = code;
        this.failureMessage = message;

        if (status == PaymentStatus.FAILED) {
            this.activeOrderId = null;
        }
    }

    /**
     * 결제 이후 업무가 실제로 완료되면 후속 처리 상태를 확정한다.
     */
    public void completeOrder() {
        this.orderStatus = OrderStatus.COMPLETE;
        this.orderStatusChangedAt = LocalDateTime.now();
    }

    /**
     * 결제 완료 이벤트 수신 후 주문 반영을 시작한다.
     */
    public void beginOrder() {
        this.orderStatus = OrderStatus.PENDING;
        this.orderStatusChangedAt = LocalDateTime.now();
    }

    /**
     * 결제 상태가 실제로 달라진 경우에만 상태 변경 시각을 갱신한다.
     */
    private void updatePaymentStatus(PaymentStatus status) {
        if (this.paymentStatus == status) {
            return;
        }

        this.paymentStatus = status;
        this.paymentStatusChangedAt = LocalDateTime.now();
    }
}
