package com.example.payments.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/payments. The token is Meridian's single-use stand-in for the card details. */
public record CreatePaymentRequest(
        @NotBlank @Size(max = 255) String token,
        @NotNull @Valid AmountDto amount) {

    @Override
    public String toString() {
        // Keep the token out of logs and stack traces.
        return "CreatePaymentRequest[amount=" + amount + "]";
    }
}
