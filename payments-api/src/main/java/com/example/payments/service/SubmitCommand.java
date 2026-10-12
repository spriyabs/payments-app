package com.example.payments.service;

/** A request to submit a payment, already stripped of web concerns. The token is never logged. */
public record SubmitCommand(String token, String amountValue, String currency) {

    @Override
    public String toString() {
        return "SubmitCommand[amount=" + amountValue + " " + currency + "]";
    }
}
