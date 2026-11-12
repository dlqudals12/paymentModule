package com.paymentModule;

import com.api.toss.TossPaymentApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(TossPaymentApi.class)
public class PaymentModuleApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentModuleApplication.class, args);
	}

}
