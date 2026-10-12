package com.example.payments.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AmountDto(
        @NotBlank
        @Pattern(regexp = "^\\d+\\.\\d{2}$", message = "must be a decimal with exactly two decimals, for example 125.50")
        String value,
        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter currency code, for example GBP")
        String currency) {
}
