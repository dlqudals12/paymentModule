package com.paymentModule.global.properties.payments;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 나이스페이 결제 API의 테스트 키와 호출 주소를 관리한다.
 */
@ConfigurationProperties("payments.nice")
@Getter
@Setter
@Component
public class NiceApiProperties {
    private String clientKey;
    private String secretKey;
    private String baseUrl;
    private Path path;

    /**
     * 결제 승인과 거래 조회가 공유하는 API 경로다.
     */
    public record Path(String payment) {
    }
}
