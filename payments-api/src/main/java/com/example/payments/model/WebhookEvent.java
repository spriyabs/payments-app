package com.example.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * One processed Meridian callback. The primary key is Meridian's {@code eventId}, so
 * inserting the same event twice fails with a unique violation: that is how duplicate
 * deliveries are detected (spec 6.2).
 *
 * <p>Implements {@link Persistable} so Spring Data issues a plain INSERT for a new event
 * instead of a SELECT-then-merge, which would hide the duplicate.
 */
@Entity
@Table(name = "webhook_event", indexes = {
        @Index(name = "idx_webhook_event_payment_id", columnList = "payment_id")
})
public class WebhookEvent implements Persistable<String> {

    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    /** The status Meridian reported in this event (may have been ignored by the state machine). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 4, nullable = false)
    private PaymentStatus status;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Transient
    private boolean isNew = true;

    protected WebhookEvent() {
        // for JPA
    }

    public WebhookEvent(String eventId, Payment payment, PaymentStatus status,
                        Instant occurredAt, Instant receivedAt) {
        this.eventId = eventId;
        this.payment = payment;
        this.status = status;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public Payment getPayment() {
        return payment;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
