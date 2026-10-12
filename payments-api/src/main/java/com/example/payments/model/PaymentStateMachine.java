package com.example.payments.model;

/**
 * Decides whether a status change is applied. Pure logic, no side effects: the
 * caller stores, logs or ignores based on the result.
 *
 * <p>Invalid transitions (late, duplicate or out-of-order webhooks) are not errors.
 * We ignore them and still answer 200 to Meridian, so a late ACCP after we
 * cancelled can never revert CANC.
 */
public final class PaymentStateMachine {

    public enum Outcome {
        /** The change is allowed; the caller should move to the new status. */
        APPLIED,
        /** The change is not allowed from the current status; keep the current status. */
        IGNORED
    }

    /** Result of a transition attempt: the outcome and the status the payment should now have. */
    public record Result(Outcome outcome, PaymentStatus status) {
        public boolean applied() {
            return outcome == Outcome.APPLIED;
        }
    }

    private PaymentStateMachine() {
    }

    public static Result transition(PaymentStatus current, PaymentStatus requested) {
        if (current == null || requested == null) {
            throw new IllegalArgumentException("current and requested status are required");
        }
        return current.canTransitionTo(requested)
                ? new Result(Outcome.APPLIED, requested)
                : new Result(Outcome.IGNORED, current);
    }
}
