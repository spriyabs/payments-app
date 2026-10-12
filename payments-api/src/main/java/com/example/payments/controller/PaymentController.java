package com.example.payments.controller;

import com.example.payments.error.ApiException;
import com.example.payments.error.ErrorCode;
import com.example.payments.model.dto.CreatePaymentRequest;
import com.example.payments.model.dto.PaymentResponse;
import com.example.payments.service.PaymentService;
import com.example.payments.service.SubmitCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        SubmitCommand command = new SubmitCommand(
                request.token(), request.amount().value(), request.amount().currency());
        return ResponseEntity.accepted().body(PaymentResponse.from(service.submit(idempotencyKey, command)));
    }

    @PostMapping("/{id}/cancellation")
    public PaymentResponse cancel(@PathVariable UUID id) {
        return PaymentResponse.from(service.cancel(id));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(service.get(id));
    }
}
