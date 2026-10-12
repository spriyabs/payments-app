package com.example.payments.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Payment status, using Meridian's codes so we never translate between vocabularies.
 *
 * <pre>
 * PDNG -> ACCP | RJCT | CANC
 * ACCP -> ACSC | CANC
 * ACSC, RJCT, CANC are final
 * </pre>
 */
public enum PaymentStatus {
    /** Pending: submitted, no outcome yet. Cancellable. */
    PDNG,
    /** Authorised. Cancellable until settlement. */
    ACCP,
    /** Settled. Final. */
    ACSC,
    /** Rejected (declined). Final. */
    RJCT,
    /** Cancelled. Final. */
    CANC;

    // Enum constants cannot be referenced from their own constructors, so the
    // table is a switch rather than a field.
    public Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case PDNG -> EnumSet.of(ACCP, RJCT, CANC);
            case ACCP -> EnumSet.of(ACSC, CANC);
            case ACSC, RJCT, CANC -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isFinal() {
        return allowedNext().isEmpty();
    }

    /** A payment can be cancelled while it is PDNG or ACCP. */
    public boolean isCancellable() {
        return canTransitionTo(CANC);
    }
}
