package com.paymentModule.data.repository.payment;

import com.paymentModule.data.model.entity.payment.Payment;
import com.paymentModule.data.enums.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    /**
     * 동일 주문의 중복 승인 요청을 막기 위해 결제 행을 잠그고 조회한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findByProviderAndActiveOrderId(PaymentProvider provider, String activeOrderId);
}
