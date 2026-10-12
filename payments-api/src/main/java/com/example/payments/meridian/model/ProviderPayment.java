package com.example.payments.meridian.model;

/** Meridian's answer to an accepted submission. Only its payment id matters: status arrives by webhook. */
public record ProviderPayment(String paymentId) {
}
