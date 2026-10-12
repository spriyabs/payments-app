package com.example.payments.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Business rules for payments, bound from {@code payments.*} (SUPPORTED_CURRENCIES, MAX_PAYMENT_AMOUNT). */
@Validated
@ConfigurationProperties(prefix = "payments")
public record PaymentsProperties(
        @NotEmpty List<@Pattern(regexp = "[A-Z]{3}") String> supportedCurrencies,
        @NotNull @DecimalMin("0.01") BigDecimal maxPaymentAmount) {
}
