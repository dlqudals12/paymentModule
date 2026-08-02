package com.paymentModule.domain.payment.controller;

import com.paymentModule.data.dto.response.SuccessResponse;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.domain.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payment", description = "결제 API")
@RestController
@RequestMapping("/apis/v3/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "일반 결제", description = "일반 결제를 요청합니다.")
    @ApiResponse(responseCode = "200", description = "결제 요청 성공")
    @Secured("ROLE_USER")
    @PostMapping
    public SuccessResponse<Object> pay(
            @Valid @RequestBody PaymentRequest request) {
        paymentService.payment(request);

        return SuccessResponse.ok();
    }
}
