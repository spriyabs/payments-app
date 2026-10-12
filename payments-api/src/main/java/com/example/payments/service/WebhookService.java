package com.example.payments.service;

import com.example.payments.model.Payment;
import com.example.payments.model.WebhookEvent;
import com.example.payments.repository.PaymentRepository;
import com.example.payments.repository.WebhookEventRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies Meridian status callbacks (spec section 6.2).
 *
 * <p>Everything for one event happens in one transaction: recording the eventId and
 * updating the payment. Duplicates, stale events and unknown payments are NOT errors:
 * they return normally so the controller answers 200 and Meridian stops retrying. Only
 * genuine failures (such as the database being down) throw, so that Meridian retries.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final int MAX_ATTEMPTS = 3;

    public enum Result {
        /** The payment moved to the reported status. */
        APPLIED,
        /** The event was stored, but the transition is not allowed from the current status. */
        IGNORED,
        /** This eventId was already processed. */
        DUPLICATE,
        /** No payment has this endToEndId. */
        UNKNOWN_PAYMENT
    }

    private final PaymentRepository payments;
    private final WebhookEventRepository events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WebhookService(PaymentRepository payments, WebhookEventRepository events,
                          TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public Result handle(StatusUpdate update) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> process(update));
            } catch (ObjectOptimisticLockingFailureException e) {
                // A cancel or another webhook updated the payment at the same moment. Try again.
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
            } catch (DataIntegrityViolationException e) {
                // Two copies of the same event arrived together and the other one won.
                if (events.existsById(update.eventId())) {
                    log.info("Webhook event {} is a duplicate", update.eventId());
                    return Result.DUPLICATE;
                }
                throw e;
            }
        }
    }

    private Result process(StatusUpdate update) {
        Payment payment = payments.findByEndToEndId(update.endToEndId()).orElse(null);
        if (payment == null) {
            log.warn("Webhook event {} is for an unknown endToEndId {}", update.eventId(), update.endToEndId());
            return Result.UNKNOWN_PAYMENT;
        }
        if (events.existsById(update.eventId())) {
            log.info("Webhook event {} is a duplicate", update.eventId());
            return Result.DUPLICATE;
        }

        events.saveAndFlush(new WebhookEvent(
                update.eventId(), payment, update.status(), update.occurredAt(), clock.instant()));

        // The webhook can arrive before our own submit call has stored Meridian's id.
        if (payment.getProviderPaymentId() == null && update.providerPaymentId() != null) {
            payment.assignProviderPaymentId(update.providerPaymentId());
        }

        boolean applied = payment.applyStatus(update.status(), update.reason());
        payments.saveAndFlush(payment);

        if (applied) {
            log.info("Payment {} (endToEndId {}) is now {} (event {})",
                    payment.getId(), payment.getEndToEndId(), update.status(), update.eventId());
            return Result.APPLIED;
        }
        log.warn("Ignored {} for payment {} (endToEndId {}): not allowed from {} (event {})",
                update.status(), payment.getId(), payment.getEndToEndId(), payment.getStatus(), update.eventId());
        return Result.IGNORED;
    }
}
