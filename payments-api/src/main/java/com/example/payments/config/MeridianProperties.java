package com.example.payments.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How we talk to Meridian. Bound from the {@code meridian.*} properties, which
 * application.yml fills from environment variables. Validated at startup, so a
 * missing value stops the app instead of failing on the first payment.
 */
@Validated
@ConfigurationProperties(prefix = "meridian")
public record MeridianProperties(
        @NotBlank String baseUrl,
        @NotBlank String apiKey,
        @NotBlank String webhookSecret,
        @NotBlank String callbackUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) int maxAttempts,
        @NotNull Duration retryBackoff) {
}
