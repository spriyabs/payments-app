package com.example.payments.meridian.client;

import com.example.payments.config.MeridianProperties;
import com.example.payments.meridian.exception.ProviderConflictException;
import com.example.payments.meridian.exception.ProviderRejectedException;
import com.example.payments.meridian.exception.ProviderUnavailableException;
import com.example.payments.meridian.model.ProviderAmount;
import com.example.payments.meridian.model.ProviderPayment;
import com.example.payments.meridian.model.ProviderStatus;
import com.example.payments.meridian.model.ProviderSubmission;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls Meridian with Spring's RestClient. Timeouts come from configuration. Network
 * errors and 5xx answers are retried with the SAME idempotency key, so a retry can never
 * create a second payment. Never logs the token, request body or response body.
 */
@Component
public class RestClientMeridianClient implements MeridianClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientMeridianClient.class);

    private final RestClient restClient;
    private final MeridianProperties properties;

    public RestClientMeridianClient(MeridianProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-Api-Key", properties.apiKey())
                .build();
    }

    @Override
    public ProviderPayment submitPayment(ProviderSubmission submission, String providerKey) {
        MeridianPaymentRequest body = new MeridianPaymentRequest(
                submission.endToEndId(), submission.amount(), submission.token(), submission.callbackUrl());
        return withRetries("submit", () -> attemptSubmit(body, providerKey));
    }

    @Override
    public void cancelPayment(String providerPaymentId, String providerKey) {
        withRetries("cancel", () -> attemptCancel(providerPaymentId, providerKey));
    }

    @Override
    public ProviderStatus getPayment(String providerPaymentId) {
        return withRetries("get", () -> attemptGet(providerPaymentId));
    }

    /** Runs an attempt, retrying network errors and 5xx answers; the caller's key stays the same. */
    private <T> T withRetries(String operation, Supplier<T> attempt) {
        for (int attempts = 1; ; attempts++) {
            try {
                return attempt.get();
            } catch (RetryableFailure failure) {
                log.warn("Meridian {} attempt {}/{} failed: {}",
                        operation, attempts, properties.maxAttempts(), failure.getMessage());
                if (attempts >= properties.maxAttempts()) {
                    throw new ProviderUnavailableException(
                            "Meridian unavailable after " + attempts + " attempts", failure);
                }
                pause();
            }
        }
    }

    private ProviderPayment attemptSubmit(MeridianPaymentRequest body, String providerKey) {
        try {
            return restClient.post()
                    .uri("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", providerKey)
                    .body(body)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            MeridianPaymentResponse parsed = response.bodyTo(MeridianPaymentResponse.class);
                            if (parsed == null || parsed.paymentId() == null) {
                                throw new RetryableFailure("Meridian answered without a payment id");
                            }
                            return new ProviderPayment(parsed.paymentId());
                        }
                        throw failureFor(status);
                    });
        } catch (ResourceAccessException e) {
            // Connection refused, reset or timed out.
            throw new RetryableFailure("network error: " + e.getClass().getSimpleName());
        }
    }

    private Boolean attemptCancel(String providerPaymentId, String providerKey) {
        try {
            return restClient.post()
                    .uri("/payments/{id}/cancellation", providerPaymentId)
                    .header("Idempotency-Key", providerKey)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            return Boolean.TRUE;
                        }
                        if (status.value() == 409) {
                            throw new ProviderConflictException("Meridian says the payment cannot be cancelled");
                        }
                        throw failureFor(status);
                    });
        } catch (ResourceAccessException e) {
            throw new RetryableFailure("network error: " + e.getClass().getSimpleName());
        }
    }

    private ProviderStatus attemptGet(String providerPaymentId) {
        try {
            return restClient.get()
                    .uri("/payments/{id}", providerPaymentId)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            MeridianStatusResponse parsed = response.bodyTo(MeridianStatusResponse.class);
                            if (parsed == null || parsed.status() == null) {
                                throw new RetryableFailure("Meridian answered without a status");
                            }
                            return new ProviderStatus(parsed.status(), parsed.statusReason());
                        }
                        throw failureFor(status);
                    });
        } catch (ResourceAccessException e) {
            throw new RetryableFailure("network error: " + e.getClass().getSimpleName());
        }
    }

    private RuntimeException failureFor(HttpStatusCode status) {
        int code = status.value();
        if (code >= 500 || code == 408 || code == 429) {
            return new RetryableFailure("Meridian answered " + code);
        }
        if (code == 401 || code == 403) {
            log.error("Meridian refused our API key (HTTP {}). Check MERIDIAN_API_KEY.", code);
            return new ProviderUnavailableException("Meridian refused our credentials");
        }
        if (code == 409) {
            // On submit this is an idempotency key clash; cancel handles its own 409 before getting here.
            log.error("Meridian reported an idempotency key conflict. This should never happen.");
            return new ProviderUnavailableException("Meridian reported an idempotency conflict");
        }
        return new ProviderRejectedException("Meridian rejected the request with HTTP " + code);
    }

    private void pause() {
        try {
            Thread.sleep(properties.retryBackoff().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("Interrupted while retrying Meridian", e);
        }
    }

    /** Internal signal: this attempt failed in a way that is worth retrying. */
    private static final class RetryableFailure extends RuntimeException {

        RetryableFailure(String message) {
            super(message, null, false, false);
        }
    }

    /** Request body for POST /payments, as defined by the Meridian spec. */
    public record MeridianPaymentRequest(String endToEndId, ProviderAmount amount, String token, String callbackUrl) {
    }

    /** Response body for POST /payments. Only the id is used. */
    public record MeridianPaymentResponse(String paymentId) {
    }

    /** Response body for GET /payments/{id}. Only the status fields are used. */
    public record MeridianStatusResponse(String status, String statusReason) {
    }
}
