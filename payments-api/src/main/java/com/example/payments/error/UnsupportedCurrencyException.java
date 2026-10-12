package com.example.payments.error;

/** The currency code is not a valid ISO 4217 code. Maps to CURRENCY_NOT_SUPPORTED (422). */
public class UnsupportedCurrencyException extends RuntimeException {
    public UnsupportedCurrencyException(String message) {
        super(message);
    }
}
