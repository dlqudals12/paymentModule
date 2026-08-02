package com.paymentModule.data.repository.payment;

import com.paymentModule.data.model.entity.payment.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
