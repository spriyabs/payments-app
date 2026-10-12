package com.example.payments.meridian.exception;

/** Meridian answered 409 to a cancellation: the payment is no longer in a cancellable state. */
public class ProviderConflictException extends RuntimeException {

    public ProviderConflictException(String message) {
        super(message);
    }
}
