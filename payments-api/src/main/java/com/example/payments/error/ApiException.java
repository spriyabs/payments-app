package com.example.payments.error;

/** An error that maps to an RFC 7807 problem response with a stable code. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Object payment;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, null);
    }

    /** {@code payment} is only set for PAYMENT_NOT_CANCELLABLE, so the caller can refresh its view. */
    public ApiException(ErrorCode code, String detail, Object payment) {
        super(detail);
        this.code = code;
        this.payment = payment;
    }

    public ErrorCode code() {
        return code;
    }

    public Object payment() {
        return payment;
    }
}
