package com.example.payments.config;

import com.example.payments.meridian.webhook.WebhookSignatureVerifier;
import com.example.payments.model.PaymentRules;
import java.time.Clock;
import java.util.LinkedHashSet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MeridianProperties.class, PaymentsProperties.class})
public class PaymentsConfig {

    /** Injected wherever the current time is needed, so tests can control it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    WebhookSignatureVerifier webhookSignatureVerifier(MeridianProperties properties) {
        return new WebhookSignatureVerifier(properties.webhookSecret());
    }

    @Bean
    PaymentRules paymentRules(PaymentsProperties properties) {
        return new PaymentRules(new LinkedHashSet<>(properties.supportedCurrencies()), properties.maxPaymentAmount());
    }
}
