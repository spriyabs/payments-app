package com.example.payments.meridian.model;

/** A payment's current status as Meridian reports it (raw codes, not yet validated). */
public record ProviderStatus(String status, String statusReason) {
}
