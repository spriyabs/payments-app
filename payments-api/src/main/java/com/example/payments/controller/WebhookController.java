package com.example.payments.controller;

import com.example.payments.error.ApiException;
import com.example.payments.error.ErrorCode;
import com.example.payments.meridian.model.MeridianEvent;
import com.example.payments.meridian.webhook.WebhookSignatureVerifier;
import com.example.payments.model.DeclineReason;
import com.example.payments.model.PaymentStatus;
import com.example.payments.service.StatusUpdate;
import com.example.payments.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Receives Meridian's status callbacks. The signature is checked on the raw bytes BEFORE
 * anything is parsed, and the body, signature and secret are never logged.
 */
@RestController
@RequestMapping("/webhooks/meridian")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookSignatureVerifier verifier;
    private final WebhookService service;
    private final ObjectMapper objectMapper;

    public WebhookController(WebhookSignatureVerifier verifier, WebhookService service, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "X-Meridian-Signature", required = false) String signature) {
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        if (!verifier.isValid(body, signature)) {
            log.warn("Webhook rejected: signature missing or invalid");
            throw new ApiException(ErrorCode.INVALID_SIGNATURE, "Webhook signature is missing or invalid");
        }
        service.handle(toStatusUpdate(parse(body)));
        return ResponseEntity.ok().build();
    }

    private MeridianEvent parse(byte[] body) {
        try {
            return objectMapper.readValue(body, MeridianEvent.class);
        } catch (JacksonException e) {
            throw invalidEvent();
        }
    }

    /** A correctly signed event that we cannot understand is a 400: retrying it can never succeed. */
    private StatusUpdate toStatusUpdate(MeridianEvent event) {
        if (isBlank(event.eventId()) || isBlank(event.endToEndId()) || isBlank(event.status())
                || event.occurredAt() == null) {
            throw invalidEvent();
        }
        PaymentStatus status;
        try {
            status = PaymentStatus.valueOf(event.status());
        } catch (IllegalArgumentException e) {
            throw invalidEvent();
        }
        return new StatusUpdate(event.eventId(), event.paymentId(), event.endToEndId(),
                status, reasonOf(event), event.occurredAt());
    }

    private DeclineReason reasonOf(MeridianEvent event) {
        if (isBlank(event.statusReason())) {
            return null;
        }
        try {
            return DeclineReason.valueOf(event.statusReason());
        } catch (IllegalArgumentException e) {
            log.warn("Event {} has an unknown status reason, treating it as NARR", event.eventId());
            return DeclineReason.NARR;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException invalidEvent() {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Webhook body is not a valid Meridian event");
    }
}
