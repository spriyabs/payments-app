package com.example.payments.meridian.model;

/** Amount in Meridian's wire format: a decimal string with exactly two decimals, plus the currency. */
public record ProviderAmount(String value, String currency) {
}
