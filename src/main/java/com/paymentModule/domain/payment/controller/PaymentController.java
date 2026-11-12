package com.paymentModule.domain.payment.controller;

import com.paymentModule.data.dto.response.SuccessResponse;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import com.paymentModule.domain.payment.dto.response.PaymentResponse;
import com.paymentModule.domain.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payment", description = "결제 API")
@RestController
@RequestMapping("/apis/v3/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 확정된 주문 식별자와 금액으로 결제 준비 정보를 저장한다.
     */
    @Operation(summary = "결제 준비", description = "확정된 주문 식별자와 금액을 저장하고 SDK 요청 정보를 반환합니다.")
    @ApiResponse(responseCode = "200", description = "결제 준비 성공")
    @Secured("ROLE_USER")
    @PostMapping("/ready")
    public SuccessResponse<PaymentResponse> ready(
            @Valid @RequestBody PaymentReadyRequest request) {
        return SuccessResponse.of(paymentService.register(request));
    }

    /**
     * SDK 인증 결과를 저장된 준비 정보와 대조하고 해당 PG 결제를 승인한다.
     */
    @Operation(summary = "결제 승인", description = "SDK 인증 결과를 검증한 뒤 해당 PG에 승인을 요청합니다.")
    @ApiResponse(responseCode = "200", description = "결제 승인 성공")
    @Secured("ROLE_USER")
    @PostMapping
    public SuccessResponse<PaymentResponse> payment(
            @Valid @RequestBody PaymentRequest request) {
        return SuccessResponse.of(paymentService.payment(request));
    }
}
