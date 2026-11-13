package com.paymentModule.data.model.entity.payment;

import com.paymentModule.data.enums.PaymentApiType;
import com.paymentModule.data.enums.PaymentProvider;
import com.paymentModule.data.model.entity.CreatedBaseEntity;
import com.paymentModule.data.model.entity.user.User;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_api_log", comment = "외부 PG사 API 요청, 응답 및 오류 통신 로그")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentApiLog extends CreatedBaseEntity {

    @Id
    @Tsid
    @Column(name = "id", nullable = false, comment = "PG사 API 통신 로그 PK")
    private Long id;

    @Column(name = "paymentId", nullable = false, comment = "결제 PK 참조값이며 DB 외래키 제약은 사용하지 않음")
    private Long paymentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paymentId", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Payment payment;

    @Column(name = "userId", nullable = false, comment = "결제 대상 사용자 PK")
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;

    @Column(name = "loginId", nullable = false, length = 255, comment = "API 호출 당시 사용자의 로그인 식별자")
    private String loginId;

    @Column(name = "transactionId", length = 255, comment = "외부 PG사에서 발급한 결제 또는 거래 식별자")
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30, comment = "호출한 결제대행사 구분값")
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "apiType", nullable = false, length = 50, comment = "PG사 API 호출 유형")
    private PaymentApiType apiType;

    @Column(name = "httpMethod", nullable = false, length = 10, comment = "PG사 API 요청 HTTP 메서드")
    private String httpMethod;

    @Column(name = "requestUrl", nullable = false, length = 1000, comment = "호출한 PG사 API 주소")
    private String requestUrl;

    @Column(name = "requestHeader", columnDefinition = "LONGTEXT", comment = "PG사 API 요청 헤더이며 인증정보와 민감정보는 저장 전 마스킹")
    private String requestHeader;

    @Column(name = "requestBody", columnDefinition = "LONGTEXT", comment = "PG사 API 요청 본문이며 개인정보와 결제정보는 저장 전 마스킹")
    private String requestBody;

    @Column(name = "responseStatus", comment = "PG사 API HTTP 응답 상태 코드")
    private Integer responseStatus;

    @Column(name = "responseHeader", columnDefinition = "LONGTEXT", comment = "PG사 API 응답 헤더이며 민감정보는 저장 전 마스킹")
    private String responseHeader;

    @Column(name = "responseBody", columnDefinition = "LONGTEXT", comment = "PG사 API 응답 본문이며 개인정보와 결제정보는 저장 전 마스킹")
    private String responseBody;

    @Builder.Default
    @Column(name = "successYn", nullable = false, comment = "PG사 API 호출 성공 여부")
    private Boolean successYn = false;

    @Column(name = "durationMs", comment = "PG사 API 요청부터 응답까지 걸린 시간, 밀리초 단위")
    private Long durationMs;

    @Column(name = "errorType", length = 100, comment = "API 호출 중 발생한 내부 예외 또는 오류 유형")
    private String errorType;

    @Column(name = "errorMessage", length = 1000, comment = "API 호출 중 발생한 내부 예외 또는 오류 메시지")
    private String errorMessage;

    @Column(name = "requestedAt", nullable = false, comment = "PG사 API 요청 시작 일시")
    private LocalDateTime requestedAt;

    @Column(name = "respondedAt", comment = "PG사 API 응답 수신 일시")
    private LocalDateTime respondedAt;
}
