package com.paymentModule.data.repository.payment;

import com.paymentModule.data.model.entity.payment.PaymentApiLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentApiLogRepository extends JpaRepository<PaymentApiLog, Long> {
}
