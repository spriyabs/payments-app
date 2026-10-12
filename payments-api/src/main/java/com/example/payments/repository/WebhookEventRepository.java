package com.example.payments.repository;

import com.example.payments.model.WebhookEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    long countByPaymentId(UUID paymentId);
}
