package com.paymentModule.data.repository.payment;

import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.enums.PaymentStatus;
import com.paymentModule.data.enums.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    /**
     * 로그인한 사용자의 결제만 최신순으로 조회한다.
     */
    Page<Payment> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * 다른 사용자의 결제는 조회 결과에서 제외한다.
     */
    Optional<Payment> findByIdAndUserId(Long id, Long userId);

    /**
     * 준비 단계에서 같은 주문의 현재 결제를 잠가 상태에 따른 재준비를 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findByOrderId(String orderId);

    /**
     * 동일 주문의 중복 승인 요청을 막기 위해 결제 행을 잠그고 조회한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findByProviderAndActiveOrderId(PaymentProvider provider, String activeOrderId);

    /**
     * 중복 이벤트가 같은 주문 반영 상태를 동시에 변경하지 않도록 결제 행을 잠근다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from Payment payment where payment.id = :paymentId")
    Optional<Payment> findLockedById(@Param("paymentId") Long paymentId);

    /**
     * 결제 완료 또는 주문 반영 대기 상태가 유예 시간보다 오래된 건을 조회한다.
     */
    @Query("""
            select payment from Payment payment
            where payment.paymentStatus = :paymentStatus
              and ((payment.orderStatus = :beforeStatus and payment.approvedAt < :cutoff)
                or (payment.orderStatus = :pendingStatus and payment.orderStatusChangedAt < :cutoff))
            """)
    List<Payment> findOrderRetryCandidates(
            @Param("paymentStatus") PaymentStatus paymentStatus,
            @Param("beforeStatus") OrderStatus beforeStatus,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("cutoff") LocalDateTime cutoff);

    /**
     * 결제 키가 있고 상태 변경 후 유예 시간이 지난 미확정 결제를 모두 조회한다.
     */
    @Query("""
            select payment from Payment payment
            where payment.paymentStatus in :statuses
              and payment.paymentStatusChangedAt < :cutoff
              and payment.externalPaymentId is not null
            """)
    List<Payment> findProcessingRecoveryCandidates(
            @Param("statuses") List<PaymentStatus> statuses,
            @Param("cutoff") LocalDateTime cutoff);
}
