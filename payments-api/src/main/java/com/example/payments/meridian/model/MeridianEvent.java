package com.example.payments.meridian.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** The body of a Meridian status callback (see the webhook in docs/meridian-openapi.yaml). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MeridianEvent(
        String eventId,
        String paymentId,
        String endToEndId,
        String status,
        String statusReason,
        Instant occurredAt) {
}
