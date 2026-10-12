package com.example.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

/**
 * A payment we submitted to Meridian. Hibernate generates the table from this class,
 * so every constraint in spec section 7 is declared here.
 *
 * <p>No card data is stored: only amount, currency, status and Meridian references.
 */
@Entity
@Table(name = "payment", indexes = {
        @Index(name = "idx_payment_provider_payment_id", columnList = "provider_payment_id")
})
@Check(constraints = "amount_minor > 0")
public class Payment implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "end_to_end_id", length = 35, nullable = false, unique = true)
    private String endToEndId;

    /** Null until Meridian accepts the submission (HTTP 202). */
    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(name = "idempotency_key", length = 64, nullable = false, unique = true)
    private String idempotencyKey;

    /** SHA-256 hex of the normalised request body, to detect key reuse with a different body. */
    @Column(name = "request_hash", columnDefinition = "char(64)", nullable = false)
    private String requestHash;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", columnDefinition = "char(3)", nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 4, nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_reason", length = 4)
    private DeclineReason statusReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic locking: a concurrent webhook and cancel cannot silently overwrite each other. */
    @Version
    private long version;

    /** Tells Spring Data this is a brand new row (INSERT), not a detached one (SELECT then merge). */
    @Transient
    private boolean isNew = true;

    protected Payment() {
        // for JPA
    }

    /** A new payment always starts as PDNG with no provider id. */
    public static Payment create(String idempotencyKey, String requestHash, Money money) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.endToEndId = p.id.toString().replace("-", "");
        p.idempotencyKey = idempotencyKey;
        p.requestHash = requestHash;
        p.amountMinor = money.minorUnits();
        p.currency = money.currencyCode();
        p.status = PaymentStatus.PDNG;
        return p;
    }

    /**
     * Applies a status change through the state machine. Returns false (and changes
     * nothing) when the transition is not allowed, e.g. a late ACCP after CANC.
     */
    public boolean applyStatus(PaymentStatus requested, DeclineReason reason) {
        PaymentStateMachine.Result result = PaymentStateMachine.transition(status, requested);
        if (!result.applied()) {
            return false;
        }
        this.status = result.status();
        this.statusReason = reason;
        return true;
    }

    public Money money() {
        return new Money(amountMinor, Money.currencyOf(currency));
    }

    public void assignProviderPaymentId(String providerPaymentId) {
        this.providerPaymentId = providerPaymentId;
    }

    @Override
    public UUID getId() {
        return id;
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

    public String getEndToEndId() {
        return endToEndId;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public DeclineReason getStatusReason() {
        return statusReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
