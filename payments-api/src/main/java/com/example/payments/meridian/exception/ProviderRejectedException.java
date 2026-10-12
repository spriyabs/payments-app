package com.example.payments.meridian.exception;

/** Meridian refused the request (for example the token is expired or already used). Not retried. */
public class ProviderRejectedException extends RuntimeException {

    public ProviderRejectedException(String message) {
        super(message);
    }
}
