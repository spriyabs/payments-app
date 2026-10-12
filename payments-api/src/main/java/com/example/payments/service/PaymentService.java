package com.example.payments.service;

import com.example.payments.config.MeridianProperties;
import com.example.payments.error.ApiException;
import com.example.payments.error.ErrorCode;
import com.example.payments.meridian.client.MeridianClient;
import com.example.payments.meridian.exception.ProviderConflictException;
import com.example.payments.meridian.exception.ProviderRejectedException;
import com.example.payments.meridian.exception.ProviderUnavailableException;
import com.example.payments.meridian.mapper.MeridianMapper;
import com.example.payments.meridian.model.ProviderAmount;
import com.example.payments.meridian.model.ProviderPayment;
import com.example.payments.meridian.model.ProviderStatus;
import com.example.payments.meridian.model.ProviderSubmission;
import com.example.payments.model.DeclineReason;
import com.example.payments.model.Money;
import com.example.payments.model.Payment;
import com.example.payments.model.PaymentRules;
import com.example.payments.model.PaymentStatus;
import com.example.payments.model.dto.PaymentResponse;
import com.example.payments.repository.PaymentRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Submits (spec section 6.1) and cancels (section 6.3) payments.
 *
 * <p>Deliberately NOT one big transaction: we commit the payment as PDNG first and only
 * then call Meridian, so a webhook that arrives before our HTTP call returns can already
 * find the payment by endToEndId.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final int MAX_UPDATE_ATTEMPTS = 3;

    private final PaymentRepository payments;
    private final MeridianClient meridian;
    private final MeridianProperties meridianProperties;
    private final PaymentRules rules;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments, MeridianClient meridian,
                          MeridianProperties meridianProperties, PaymentRules rules, TransactionTemplate tx) {
        this.payments = payments;
        this.meridian = meridian;
        this.meridianProperties = meridianProperties;
        this.rules = rules;
        this.tx = tx;
    }

    public Payment submit(String idempotencyKey, SubmitCommand command) {
        Money money = Money.parse(command.amountValue(), command.currency());
        rules.check(money);
        ProviderAmount providerAmount = MeridianMapper.toProviderAmount(money);
        String requestHash = requestHash(command.token(), money);

        Payment payment = payments.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> createPending(idempotencyKey, requestHash, money));

        if (!payment.getRequestHash().equals(requestHash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "Idempotency-Key was already used with a different request body");
        }

        boolean neverReachedMeridian = payment.getStatus() == PaymentStatus.PDNG
                && payment.getProviderPaymentId() == null;
        if (!neverReachedMeridian) {
            // Same key, same body: return the original payment without calling Meridian again.
            return payment;
        }
        return sendToMeridian(payment, command.token(), providerAmount);
    }

    public Payment get(UUID id) {
        return payments.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "No payment with that id"));
    }

    /**
     * Cancels a payment (spec section 6.3). Repeating the call is safe: an already cancelled
     * payment returns as is, and the provider key {@code cancel-{id}} is stable.
     */
    public Payment cancel(UUID id) {
        Payment payment = get(id);
        if (payment.getStatus() == PaymentStatus.CANC) {
            return payment;
        }
        if (!payment.getStatus().isCancellable()) {
            throw notCancellable(payment);
        }
        if (payment.getProviderPaymentId() == null) {
            // Meridian cancels by its own payment id. Without one we cannot be sure the
            // submission never reached Meridian, so we do not pretend it is cancelled.
            throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE,
                    "The payment has not been confirmed by the provider yet. Repeat the original "
                            + "submit request, then cancel again.");
        }

        try {
            meridian.cancelPayment(payment.getProviderPaymentId(), "cancel-" + id);
        } catch (ProviderConflictException e) {
            return refreshAfterConflict(payment);
        } catch (ProviderRejectedException e) {
            log.warn("Cancel of payment {} rejected by Meridian", id);
            throw new ApiException(ErrorCode.PROVIDER_REJECTED, "The payment provider rejected the cancellation");
        } catch (ProviderUnavailableException e) {
            log.warn("Cancel of payment {} not sent: Meridian unavailable", id);
            throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE,
                    "Payment provider is unavailable, please try again");
        }

        Payment cancelled = update(id, p -> p.applyStatus(PaymentStatus.CANC, null));
        log.info("Payment {} cancelled (status now {})", id, cancelled.getStatus());
        return cancelled;
    }

    /**
     * Meridian said 409: our view is stale (for example a missed ACSC webhook). Copy
     * Meridian's status, then report the outcome from the refreshed payment.
     */
    private Payment refreshAfterConflict(Payment payment) {
        ProviderStatus remote;
        try {
            remote = meridian.getPayment(payment.getProviderPaymentId());
        } catch (ProviderRejectedException | ProviderUnavailableException e) {
            throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE,
                    "Payment provider is unavailable, please try again");
        }
        Payment refreshed = update(payment.getId(), p -> applyRemote(p, remote));
        log.info("Payment {} refreshed from Meridian after a cancel conflict (status now {})",
                refreshed.getId(), refreshed.getStatus());
        if (refreshed.getStatus() == PaymentStatus.CANC) {
            return refreshed;
        }
        throw notCancellable(refreshed);
    }

    private static void applyRemote(Payment payment, ProviderStatus remote) {
        PaymentStatus status;
        try {
            status = PaymentStatus.valueOf(remote.status());
        } catch (IllegalArgumentException e) {
            log.warn("Meridian reported an unknown status for payment {}", payment.getId());
            return;
        }
        payment.applyStatus(status, status == PaymentStatus.RJCT ? reasonOf(remote.statusReason()) : null);
    }

    private static DeclineReason reasonOf(String code) {
        try {
            return code == null ? DeclineReason.NARR : DeclineReason.valueOf(code);
        } catch (IllegalArgumentException e) {
            return DeclineReason.NARR;
        }
    }

    private static ApiException notCancellable(Payment payment) {
        return new ApiException(ErrorCode.PAYMENT_NOT_CANCELLABLE,
                "Payment is " + payment.getStatus() + " and can no longer be cancelled",
                PaymentResponse.from(payment));
    }

    /** Commits a new PDNG payment. If a concurrent request with the same key won, returns that one. */
    private Payment createPending(String idempotencyKey, String requestHash, Money money) {
        try {
            return tx.execute(status ->
                    payments.saveAndFlush(Payment.create(idempotencyKey, requestHash, money)));
        } catch (DataIntegrityViolationException e) {
            return payments.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
        }
    }

    /**
     * Calls Meridian using the payment id as the provider idempotency key. The key is
     * stable, so repeating this after a timeout or a 502 can never charge twice.
     */
    private Payment sendToMeridian(Payment payment, String token, ProviderAmount amount) {
        ProviderSubmission submission = new ProviderSubmission(
                payment.getEndToEndId(), amount, token, meridianProperties.callbackUrl());
        try {
            ProviderPayment accepted = meridian.submitPayment(submission, payment.getId().toString());
            log.info("Payment {} accepted by Meridian (endToEndId {})", payment.getId(), payment.getEndToEndId());
            return update(payment.getId(), p -> p.assignProviderPaymentId(accepted.paymentId()));
        } catch (ProviderRejectedException e) {
            log.warn("Payment {} rejected by Meridian (endToEndId {})", payment.getId(), payment.getEndToEndId());
            update(payment.getId(), p -> p.applyStatus(PaymentStatus.RJCT, DeclineReason.NARR));
            throw new ApiException(ErrorCode.PROVIDER_REJECTED,
                    "The payment provider rejected the request. The token may be expired or already used.");
        } catch (ProviderUnavailableException e) {
            log.warn("Payment {} not sent: Meridian unavailable (endToEndId {})",
                    payment.getId(), payment.getEndToEndId());
            throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE,
                    "Payment provider is unavailable, please try again");
        }
    }

    /** Reloads the payment and applies a change, retrying if a webhook updated it at the same moment. */
    private Payment update(UUID id, Consumer<Payment> change) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> {
                    Payment payment = payments.findById(id).orElseThrow();
                    change.accept(payment);
                    return payments.saveAndFlush(payment);
                });
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= MAX_UPDATE_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    /**
     * Fingerprint of the request body, so the same key with a different body is detected.
     * Only a hash is stored; the token itself is never saved.
     */
    static String requestHash(String token, Money money) {
        String normalised = token + "|" + money.minorUnits() + "|" + money.currencyCode();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
