package com.example.payments.model.dto;

import com.example.payments.model.DeclineReason;
import com.example.payments.model.Money;
import com.example.payments.model.Payment;
import com.example.payments.model.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/** A payment as returned by our API. Holds no card information of any kind. */
public record PaymentResponse(
        UUID id,
        String endToEndId,
        AmountDto amount,
        PaymentStatus status,
        DeclineReason statusReason,
        boolean cancellable,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment payment) {
        Money money = payment.money();
        return new PaymentResponse(
                payment.getId(),
                payment.getEndToEndId(),
                new AmountDto(money.toAmountString(), money.currencyCode()),
                payment.getStatus(),
                payment.getStatusReason(),
                payment.getStatus().isCancellable(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
