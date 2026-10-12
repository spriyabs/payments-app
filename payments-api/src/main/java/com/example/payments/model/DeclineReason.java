package com.example.payments.model;

/** Why Meridian rejected a payment, with the message we show callers. */
public enum DeclineReason {
    AM04("Insufficient funds"),
    AC04("Card is closed or blocked"),
    FF01("Card details are invalid"),
    NARR("Payment could not be completed");

    private final String message;

    DeclineReason(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
