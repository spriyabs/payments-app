package com.example.payments.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.payments.TestcontainersConfiguration;
import com.example.payments.meridian.webhook.WebhookSignatureVerifier;
import com.example.payments.model.DeclineReason;
import com.example.payments.model.Money;
import com.example.payments.model.Payment;
import com.example.payments.model.PaymentStatus;
import com.example.payments.repository.PaymentRepository;
import com.example.payments.repository.WebhookEventRepository;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.charset.StandardCharsets;
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
 * The webhook endpoint through real HTTP, real Postgres and a fake Meridian.
 * Covers spec section 12 rows 1 to 5 and the acceptance scenarios for webhooks.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "meridian.webhook-secret=test-webhook-secret",
                "meridian.retry-backoff=10ms"
        })
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestRestTemplate
class WebhookTest {

    private static final String SECRET = "test-webhook-secret";

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

    @Autowired
    WebhookEventRepository events;

    private final WebhookSignatureVerifier signer = new WebhookSignatureVerifier(SECRET);

    @BeforeEach
    void resetMeridian() {
        meridian.resetAll();
    }

    // ---- helpers --------------------------------------------------------

    private Payment newPayment() {
        Payment payment = Payment.create("key-" + UUID.randomUUID(), "a".repeat(64), Money.parse("125.50", "GBP"));
        return payments.saveAndFlush(payment);
    }

    private Payment reload(Payment payment) {
        return payments.findById(payment.getId()).orElseThrow();
    }

    private static String event(String eventId, String endToEndId, String status, String reason) {
        String reasonJson = reason == null ? "null" : "\"" + reason + "\"";
        return "{\"eventId\":\"" + eventId + "\",\"paymentId\":\"pay_meridian_1\",\"endToEndId\":\""
                + endToEndId + "\",\"status\":\"" + status + "\",\"statusReason\":" + reasonJson
                + ",\"occurredAt\":\"2026-10-09T10:00:00Z\"}";
    }

    private static String eventId() {
        return "evt_" + UUID.randomUUID();
    }

    private ResponseEntity<JsonNode> post(String body, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            headers.set("X-Meridian-Signature", signature);
        }
        return rest.exchange("/webhooks/meridian", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> postSigned(String body) {
        return post(body, signer.sign(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
    }

    // ---- status changes ---------------------------------------------------

    @Test
    void anAuthorisedWebhookMovesThePaymentToAccpAndItStaysCancellable() {
        Payment payment = newPayment();

        ResponseEntity<JsonNode> response = postSigned(event(eventId(), payment.getEndToEndId(), "ACCP", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.ACCP);
        JsonNode api = rest.getForObject("/api/payments/" + payment.getId(), JsonNode.class);
        assertThat(api.get("status").asString()).isEqualTo("ACCP");
        assertThat(api.get("cancellable").asBoolean()).isTrue();
        assertThat(events.countByPaymentId(payment.getId())).isEqualTo(1);
    }

    @Test
    void aSubmittedPaymentProgressesToAuthorisedAndThenSettled() {
        meridian.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/payments").willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"paymentId\":\"pay_meridian_1\"}")));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "flow-" + UUID.randomUUID());
        String json = "{\"token\":\"tok_flow\",\"amount\":{\"value\":\"125.50\",\"currency\":\"GBP\"}}";

        ResponseEntity<JsonNode> created = rest.exchange(
                "/api/payments", HttpMethod.POST, new HttpEntity<>(json, headers), JsonNode.class);

        assertThat(created.getStatusCode().value()).isEqualTo(202);
        assertThat(created.getBody().get("status").asString()).isEqualTo("PDNG");
        String id = created.getBody().get("id").asString();
        String endToEndId = created.getBody().get("endToEndId").asString();

        postSigned(event(eventId(), endToEndId, "ACCP", null));
        assertThat(rest.getForObject("/api/payments/" + id, JsonNode.class).get("status").asString())
                .isEqualTo("ACCP");

        postSigned(event(eventId(), endToEndId, "ACSC", null));
        JsonNode settled = rest.getForObject("/api/payments/" + id, JsonNode.class);
        assertThat(settled.get("status").asString()).isEqualTo("ACSC");
        assertThat(settled.get("cancellable").asBoolean()).isFalse();
    }

    @Test
    void aDeclineIsRecordedWithItsReason() {
        Payment payment = newPayment();

        postSigned(event(eventId(), payment.getEndToEndId(), "RJCT", "AM04"));

        Payment saved = reload(payment);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.RJCT);
        assertThat(saved.getStatusReason()).isEqualTo(DeclineReason.AM04);
        JsonNode api = rest.getForObject("/api/payments/" + payment.getId(), JsonNode.class);
        assertThat(api.get("statusReason").asString()).isEqualTo("AM04");
        assertThat(api.get("cancellable").asBoolean()).isFalse();
    }

    @Test
    void anUnknownDeclineReasonIsTreatedAsNarr() {
        Payment payment = newPayment();

        postSigned(event(eventId(), payment.getEndToEndId(), "RJCT", "ZZ99"));

        assertThat(reload(payment).getStatusReason()).isEqualTo(DeclineReason.NARR);
    }

    @Test
    void aWebhookThatArrivesBeforeTheProviderIdIsSavedStillMatchesAndStoresIt() {
        Payment payment = newPayment();
        assertThat(payment.getProviderPaymentId()).isNull();

        postSigned(event(eventId(), payment.getEndToEndId(), "ACCP", null));

        Payment saved = reload(payment);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.ACCP);
        assertThat(saved.getProviderPaymentId()).isEqualTo("pay_meridian_1");
    }

    // ---- duplicates, stale and unknown -------------------------------------

    @Test
    void theSameWebhookTwiceIsStoredOnceAndLeavesTheStateUnchanged() {
        Payment payment = newPayment();
        String accepted = event(eventId(), payment.getEndToEndId(), "ACCP", null);

        assertThat(postSigned(accepted).getStatusCode().value()).isEqualTo(200);
        long versionAfterFirst = reload(payment).getVersion();
        assertThat(postSigned(accepted).getStatusCode().value()).isEqualTo(200);

        assertThat(events.countByPaymentId(payment.getId())).isEqualTo(1);
        Payment saved = reload(payment);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.ACCP);
        assertThat(saved.getVersion()).isEqualTo(versionAfterFirst);
    }

    @Test
    void aReplayedOldEventNeverMovesAPaymentBackwards() {
        Payment payment = newPayment();
        String accepted = event(eventId(), payment.getEndToEndId(), "ACCP", null);
        postSigned(accepted);
        postSigned(event(eventId(), payment.getEndToEndId(), "ACSC", null));

        postSigned(accepted);

        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.ACSC);
        assertThat(events.countByPaymentId(payment.getId())).isEqualTo(2);
    }

    @Test
    void aLateAuthorisationAfterACancelIsStoredButIgnored() {
        Payment payment = newPayment();
        payment.applyStatus(PaymentStatus.CANC, null);
        payments.saveAndFlush(payment);

        ResponseEntity<JsonNode> response = postSigned(event(eventId(), payment.getEndToEndId(), "ACCP", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.CANC);
        assertThat(events.countByPaymentId(payment.getId())).isEqualTo(1);
    }

    @Test
    void aWebhookForAnUnknownPaymentIsAcknowledgedAndNothingIsStored() {
        long before = events.count();

        ResponseEntity<JsonNode> response = postSigned(event(eventId(), "doesnotexist", "ACCP", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(events.count()).isEqualTo(before);
    }

    // ---- signature ---------------------------------------------------------

    @Test
    void aMismatchedSignatureIsRejectedAndNothingChanges() {
        Payment payment = newPayment();
        String body = event(eventId(), payment.getEndToEndId(), "ACCP", null);
        String wrong = new WebhookSignatureVerifier("some-other-secret").sign(body.getBytes(StandardCharsets.UTF_8));

        ResponseEntity<JsonNode> response = post(body, wrong);

        assertProblem(response, 401, "INVALID_SIGNATURE");
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.PDNG);
        assertThat(events.countByPaymentId(payment.getId())).isZero();
    }

    @Test
    void aSignatureForADifferentBodyIsRejected() {
        Payment payment = newPayment();
        String signedBody = event(eventId(), payment.getEndToEndId(), "ACCP", null);
        String tampered = signedBody.replace("ACCP", "ACSC");

        ResponseEntity<JsonNode> response = post(tampered, signer.sign(signedBody.getBytes(StandardCharsets.UTF_8)));

        assertProblem(response, 401, "INVALID_SIGNATURE");
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.PDNG);
    }

    @Test
    void aMissingSignatureOrBodyIsRejected() {
        Payment payment = newPayment();

        assertProblem(post(event(eventId(), payment.getEndToEndId(), "ACCP", null), null), 401, "INVALID_SIGNATURE");
        assertProblem(post("", null), 401, "INVALID_SIGNATURE");
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.PDNG);
    }

    // ---- bad but correctly signed bodies ------------------------------------

    @Test
    void aSignedBodyThatIsNotAValidEventIsAValidationError() {
        Payment payment = newPayment();

        assertProblem(postSigned("not json"), 400, "VALIDATION_ERROR");
        assertProblem(postSigned(event(eventId(), payment.getEndToEndId(), "WEIRD", null)), 400, "VALIDATION_ERROR");
        assertProblem(postSigned("{\"eventId\":\"e1\",\"status\":\"ACCP\"}"), 400, "VALIDATION_ERROR");
        assertThat(reload(payment).getStatus()).isEqualTo(PaymentStatus.PDNG);
    }
}
