package com.example.payments.meridian.client;

import com.example.payments.meridian.exception.ProviderConflictException;
import com.example.payments.meridian.exception.ProviderRejectedException;
import com.example.payments.meridian.exception.ProviderUnavailableException;
import com.example.payments.meridian.model.ProviderPayment;
import com.example.payments.meridian.model.ProviderStatus;
import com.example.payments.meridian.model.ProviderSubmission;

/** Our server-to-server calls to Meridian. */
public interface MeridianClient {

    /**
     * Requests authorisation. Safe to call again with the same {@code providerKey}:
     * Meridian treats it as the same request.
     *
     * @throws ProviderRejectedException    Meridian refused the request
     * @throws ProviderUnavailableException Meridian unreachable or failing after retries
     */
    ProviderPayment submitPayment(ProviderSubmission submission, String providerKey);

    /**
     * Cancels a payment. Safe to repeat with the same {@code providerKey}.
     *
     * @throws ProviderConflictException    the payment is already settled or rejected
     * @throws ProviderRejectedException    Meridian refused the request (for example unknown payment)
     * @throws ProviderUnavailableException Meridian unreachable or failing after retries
     */
    void cancelPayment(String providerPaymentId, String providerKey);

    /**
     * Reads the payment's current status from Meridian.
     *
     * @throws ProviderRejectedException    Meridian refused the request (for example unknown payment)
     * @throws ProviderUnavailableException Meridian unreachable or failing after retries
     */
    ProviderStatus getPayment(String providerPaymentId);
}
