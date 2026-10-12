package com.example.payments.service;

import com.example.payments.model.DeclineReason;
import com.example.payments.model.PaymentStatus;
import java.time.Instant;

/** A validated status change reported by Meridian. */
public record StatusUpdate(
        String eventId,
        String providerPaymentId,
        String endToEndId,
        PaymentStatus status,
        DeclineReason reason,
        Instant occurredAt) {
}
