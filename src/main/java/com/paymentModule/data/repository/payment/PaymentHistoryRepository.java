package com.paymentModule.data.repository.payment;

import com.paymentModule.data.model.entity.payment.PaymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {
}
