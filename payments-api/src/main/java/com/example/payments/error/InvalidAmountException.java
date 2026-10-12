package com.example.payments.error;

/** The amount is malformed, not positive, has too many decimals, or is too large. Maps to INVALID_AMOUNT (422). */
public class InvalidAmountException extends RuntimeException {
    public InvalidAmountException(String message) {
        super(message);
    }
}
