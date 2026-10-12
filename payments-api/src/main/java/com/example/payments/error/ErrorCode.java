package com.example.payments.error;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes. Keep in sync with ErrorCode in docs/openapi.yaml. */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT),
    PAYMENT_NOT_CANCELLABLE(HttpStatus.CONFLICT),
    INVALID_AMOUNT(HttpStatus.UNPROCESSABLE_ENTITY),
    CURRENCY_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY),
    PROVIDER_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY),
    PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
