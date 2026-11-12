package com.paymentModule.global.properties.payments;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties("payments.toss")
@Getter
@Setter
@Component
public class TossApiProperties {
    private String clientKey;
    private String secretKey;
    private String securityKey;
    private String baseUrl;
    private Path path;

    public record Path(String confirm, String paymentKey, String orderId, String cancel, String virtualAccounts) {
    }
}
