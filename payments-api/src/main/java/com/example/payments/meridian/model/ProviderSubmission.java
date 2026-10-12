package com.example.payments.meridian.model;

/** What we send to Meridian to request authorisation. The token is never logged or stored. */
public record ProviderSubmission(String endToEndId, ProviderAmount amount, String token, String callbackUrl) {

    @Override
    public String toString() {
        // Keep the token out of logs and stack traces.
        return "ProviderSubmission[endToEndId=" + endToEndId + ", amount=" + amount + "]";
    }
}
