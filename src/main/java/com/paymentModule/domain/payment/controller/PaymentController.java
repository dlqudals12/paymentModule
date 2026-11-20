package com.paymentModule.domain.payment.controller;

import com.paymentModule.data.dto.response.SuccessResponse;
import com.paymentModule.domain.payment.dto.request.PaymentRequest;
import com.paymentModule.domain.payment.dto.request.PaymentReadyRequest;
import com.paymentModule.domain.payment.dto.response.PaymentResponse;
import com.paymentModule.domain.payment.service.PaymentService;
import com.paymentModule.global.properties.payments.TossApiProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Page;

@Tag(name = "Payment", description = "결제 API")
@RestController
@RequestMapping("/apis/v3/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final TossApiProperties tossApiProperties;

    /**
     * 브라우저 SDK 초기화에 필요한 토스 공개 클라이언트 키를 반환한다.
     */
    @Operation(summary = "토스 클라이언트 키", description = "로그인한 사용자에게 브라우저 SDK용 공개 키를 반환합니다.")
    @ApiResponse(responseCode = "200", description = "클라이언트 키 조회 성공")
    @Secured("ROLE_USER")
    @GetMapping("/toss/client-key")
    public SuccessResponse<String> clientKey() {
        return SuccessResponse.of(tossApiProperties.getClientKey());
    }

    /**
     * 로그인한 사용자의 결제 목록을 최신순으로 조회한다.
     */
    @Operation(summary = "내 결제 목록", description = "로그인한 사용자의 저장된 결제를 20건씩 조회합니다.")
    @ApiResponse(responseCode = "200", description = "결제 목록 조회 성공")
    @Secured("ROLE_USER")
    @GetMapping
    public SuccessResponse<Page<PaymentResponse>> payments(@RequestParam(defaultValue = "0") int page) {
        return SuccessResponse.of(paymentService.payments(Math.max(page, 0)));
    }

    /**
     * 로그인한 사용자의 결제 한 건을 조회한다.
     */
    @Operation(summary = "내 결제 조회", description = "로그인한 사용자의 결제 한 건을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "결제 조회 성공")
    @Secured("ROLE_USER")
    @GetMapping("/{paymentId}")
    public SuccessResponse<PaymentResponse> payment(@PathVariable Long paymentId) {
        return SuccessResponse.of(paymentService.payment(paymentId));
    }

    /**
     * 주문 식별자로 서버 금액을 계산하고 결제 준비 정보를 저장한다.
     */
    @Operation(summary = "결제 준비", description = "주문 식별자로 금액을 계산해 저장하고 SDK 요청 정보를 반환합니다.")
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
