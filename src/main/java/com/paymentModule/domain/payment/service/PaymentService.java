package com.paymentModule.domain.payment.service;

import com.paymentModule.data.repository.payment.PaymentRepository;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final UserService userService;
    private final PaymentRepository paymentRepository;

    /**
     * 결제
     */
    public void payment(PaymentRequest paymentRequest) {

    }
}
