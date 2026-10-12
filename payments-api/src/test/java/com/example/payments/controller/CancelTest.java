package com.example.payments.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.payments.TestcontainersConfiguration;
import com.example.payments.model.DeclineReason;
import com.example.payments.model.Money;
import com.example.payments.model.Payment;
import com.example.payments.model.PaymentStatus;
import com.example.payments.repository.PaymentRepository;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * POST /api/payments/{id}/cancellation through real HTTP, real Postgres and a fake Meridian.
 * Covers spec section 6.3 and section 12 rows 6 to 8.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "meridian.api-key=test-api-key",
                "meridian.max-attempts=3",
                "meridian.retry-backoff=10ms",
                "meridian.read-timeout=2s"
        })
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestRestTemplate
class CancelTest {

    private static final String PROVIDER_ID = "pay_meridian_1";

    @RegisterExtension
    static WireMockExtension meridian = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void meridianUrl(DynamicPropertyRegistry registry) {
        registry.add("meridian.base-url", meridian::baseUrl);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    PaymentRepository payments;

    @BeforeEach
    void resetMeridian() {
        meridian.resetAll();
    }

    // ---- helpers --------------------------------------------------------

    /** Saves a payment that Meridian has accepted and that has reached the given status. */
    private Payment payment(PaymentStatus target) {
        Payment payment = Payment.create("key-" + UUID.randomUUID(), "a".repeat(64), Money.parse("125.50", "GBP"));
        payment.assignProviderPaymentId(PROVIDER_ID);
        switch (target) {
            case PDNG -> { }
            case ACCP -> payment.applyStatus(PaymentStatus.ACCP, null);
            case ACSC -> {
                payment.applyStatus(PaymentStatus.ACCP, null);
                payment.applyStatus(PaymentStatus.ACSC, null);
            }
            case RJCT -> payment.applyStatus(PaymentStatus.RJCT, DeclineReason.AM04);
            case CANC -> payment.applyStatus(PaymentStatus.CANC, null);
        }
        return payments.saveAndFlush(payment);
    }

    private Payment reload(Payment payment) {
        return payments.findById(payment.getId()).orElseThrow();
    }

    private void stubCancelOk() {
        meridian.stubFor(post("/payments/" + PROVIDER_ID + "/cancellation").willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"paymentId\":\"" + PROVIDER_ID + "\",\"status\":\"CANC\"}")));
    }

    private void stubCancelConflict() {
        meridian.stubFor(post("/payments/" + PROVIDER_ID + "/cancellation").willReturn(aResponse()
                .withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"NOT_CANCELLABLE\",\"message\":\"final\"}")));
    }

    private void stubMeridianStatus(String status) {
        meridian.stubFor(get("/payments/" + PROVIDER_ID).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"paymentId\":\"" + PROVIDER_ID + "\",\"status\":\"" + status + "\",\"statusReason\":null}")));
    }

    private ResponseEntity<JsonNode> cancel(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> cancel(Payment payment) {
        return cancel("/api/payments/" + payment.getId() + "/cancellation");
    }

    private int cancelCalls() {
        return meridian.findAll(postRequestedFor(urlEqualTo("/payments/" + PROVIDER_ID + "/cancellation"))).size();
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
    }

    // ---- success ----------------------------------------------------------

    @Test
    void cancellingAPendingPaymentCallsMeridianWithAStableKeyAndReturnsCancelled() {
        Payment payment = payment(PaymentStatus.PDNG);
        stubCancelOk();

        ResponseEntity<JsonNode> response = cancel(payment);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status").asString()).isEqualTo("CANC");
        assertThat(response.getBody().get("cancellable").asBoolean()).isFalse();
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.CANC);
        meridian.verify(1, postRequestedFor(urlEqualTo("/payments/" + PROVIDER_ID + "/cancellation"))
                .withHeader("Idempotency-Key", equalTo("cancel-" + payment.getId()))
                .withHeader("X-Api-Key", equalTo("test-api-key")));
    }

    @Test
    void cancellingAnAuthorisedPaymentWorks() {
        Payment payment = payment(PaymentStatus.ACCP);
        stubCancelOk();

        ResponseEntity<JsonNode> response = cancel(payment);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status").asString()).isEqualTo("CANC");
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.CANC);
    }

    @Test
    void cancellingTwiceReturnsOkBothTimesAndCallsMeridianOnce() {
        Payment payment = payment(PaymentStatus.ACCP);
        stubCancelOk();

        ResponseEntity<JsonNode> first = cancel(payment);
        ResponseEntity<JsonNode> second = cancel(payment);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().get("status").asString()).isEqualTo("CANC");
        assertThat(cancelCalls()).isEqualTo(1);
    }

    // ---- not cancellable --------------------------------------------------

    @Test
    void aSettledPaymentCannotBeCancelledAndTheBodyCarriesThePayment() {
        Payment payment = payment(PaymentStatus.ACSC);

        ResponseEntity<JsonNode> response = cancel(payment);

        assertProblem(response, 409, "PAYMENT_NOT_CANCELLABLE");
        assertThat(response.getBody().get("payment").get("status").asString()).isEqualTo("ACSC");
        assertThat(response.getBody().get("payment").get("cancellable").asBoolean()).isFalse();
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.ACSC);
        assertThat(cancelCalls()).isZero();
    }

    @Test
    void aRejectedPaymentCannotBeCancelled() {
        Payment payment = payment(PaymentStatus.RJCT);

        ResponseEntity<JsonNode> response = cancel(payment);

        assertProblem(response, 409, "PAYMENT_NOT_CANCELLABLE");
        assertThat(response.getBody().get("payment").get("status").asString()).isEqualTo("RJCT");
        assertThat(cancelCalls()).isZero();
    }

    @Test
    void whenMeridianSaysConflictTheLocalStatusIsRefreshedFromMeridian() {
        // We still think ACCP because the ACSC webhook was missed.
        Payment payment = payment(PaymentStatus.ACCP);
        stubCancelConflict();
        stubMeridianStatus("ACSC");

        ResponseEntity<JsonNode> response = cancel(payment);

        assertProblem(response, 409, "PAYMENT_NOT_CANCELLABLE");
        assertThat(response.getBody().get("payment").get("status").asString()).isEqualTo("ACSC");
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.ACSC);
    }

    @Test
    void whenMeridianSaysConflictBecauseItIsAlreadyCancelledTheCancelSucceeds() {
        // An earlier cancel reached Meridian but its answer was lost.
        Payment payment = payment(PaymentStatus.ACCP);
        stubCancelConflict();
        stubMeridianStatus("CANC");

        ResponseEntity<JsonNode> response = cancel(payment);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status").asString()).isEqualTo("CANC");
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.CANC);
    }

    // ---- errors -----------------------------------------------------------

    @Test
    void anUnknownPaymentIsNotFound() {
        assertProblem(cancel("/api/payments/" + UUID.randomUUID() + "/cancellation"), 404, "PAYMENT_NOT_FOUND");
        assertProblem(cancel("/api/payments/not-a-uuid/cancellation"), 404, "PAYMENT_NOT_FOUND");
    }

    @Test
    void whenMeridianIsDownTheStateIsUnchangedAndRetryingUsesTheSameKey() {
        Payment payment = payment(PaymentStatus.ACCP);
        meridian.stubFor(post("/payments/" + PROVIDER_ID + "/cancellation").willReturn(serverError()));

        ResponseEntity<JsonNode> failed = cancel(payment);

        assertProblem(failed, 502, "PROVIDER_UNAVAILABLE");
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.ACCP);
        assertThat(cancelCalls()).isEqualTo(3);

        meridian.resetAll();
        stubCancelOk();
        ResponseEntity<JsonNode> retried = cancel(payment);

        assertThat(retried.getStatusCode().value()).isEqualTo(200);
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.CANC);
        meridian.verify(1, postRequestedFor(urlEqualTo("/payments/" + PROVIDER_ID + "/cancellation"))
                .withHeader("Idempotency-Key", equalTo("cancel-" + payment.getId())));
    }

    @Test
    void aPaymentMeridianNeverConfirmedCannotBeCancelledYet() {
        Payment payment = Payment.create("key-" + UUID.randomUUID(), "a".repeat(64), Money.parse("10.00", "GBP"));
        payments.saveAndFlush(payment);

        ResponseEntity<JsonNode> response = cancel(payment);

        assertProblem(response, 502, "PROVIDER_UNAVAILABLE");
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.PDNG);
        assertThat(cancelCalls()).isZero();
    }

    @Test
    void whenMeridianDoesNotKnowThePaymentTheCancelIsRejected() {
        Payment payment = payment(PaymentStatus.PDNG);
        meridian.stubFor(post("/payments/" + PROVIDER_ID + "/cancellation").willReturn(aResponse().withStatus(404)));

        ResponseEntity<JsonNode> response = cancel(payment);

        assertProblem(response, 422, "PROVIDER_REJECTED");
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.PDNG);
    }
}
