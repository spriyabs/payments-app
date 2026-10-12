package com.example.payments.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.payments.TestcontainersConfiguration;
import com.example.payments.model.DeclineReason;
import com.example.payments.model.Payment;
import com.example.payments.model.PaymentStatus;
import com.example.payments.repository.PaymentRepository;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * End to end through HTTP, real Postgres (Testcontainers) and a fake Meridian (WireMock).
 * Covers spec section 12 rows 10 to 16.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "meridian.api-key=test-api-key",
                "meridian.callback-url=http://payments-api:8080/webhooks/meridian",
                "meridian.max-attempts=3",
                "meridian.retry-backoff=10ms",
                "meridian.read-timeout=2s",
                // JPY is allowed by us but cannot be sent to Meridian (2-decimal schema).
                "payments.supported-currencies=GBP,EUR,JPY",
                "payments.max-payment-amount=10000.00"
        })
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestRestTemplate
class PaymentSubmissionTest {

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

    private void stubAccepted() {
        meridian.stubFor(post("/payments").willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"paymentId\":\"pay_abc123\",\"status\":\"PDNG\"}")));
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static String body(String token, String value, String currency) {
        return "{\"token\":\"" + token + "\",\"amount\":{\"value\":\"" + value + "\",\"currency\":\"" + currency + "\"}}";
    }

    private ResponseEntity<JsonNode> submit(String key, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.exchange("/api/payments", HttpMethod.POST, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return rest.exchange(path, HttpMethod.GET, null, JsonNode.class);
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
        assertThat(response.getBody().get("status").asInt()).isEqualTo(status);
    }

    private List<LoggedRequest> meridianRequests() {
        return meridian.findAll(postRequestedFor(urlEqualTo("/payments")));
    }

    // ---- submit: happy path ----------------------------------------------

    @Test
    void submitCreatesAPendingPaymentAndCallsMeridian() {
        stubAccepted();
        String key = newKey();

        ResponseEntity<JsonNode> response = submit(key, body("tok_happy", "125.50", "GBP"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        JsonNode payment = response.getBody();
        assertThat(payment.get("status").asString()).isEqualTo("PDNG");
        assertThat(payment.get("statusReason").isNull()).isTrue();
        assertThat(payment.get("cancellable").asBoolean()).isTrue();
        assertThat(payment.get("amount").get("value").asString()).isEqualTo("125.50");
        assertThat(payment.get("amount").get("currency").asString()).isEqualTo("GBP");
        assertThat(payment.get("endToEndId").asString()).hasSize(32);
        assertThat(response.getBody().toString()).doesNotContain("tok_happy");

        Payment saved = payments.findByIdempotencyKey(key).orElseThrow();
        assertThat(saved.getId().toString()).isEqualTo(payment.get("id").asString());
        assertThat(saved.getProviderPaymentId()).isEqualTo("pay_abc123");
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PDNG);

        meridian.verify(1, postRequestedFor(urlEqualTo("/payments"))
                .withHeader("Idempotency-Key", equalTo(saved.getId().toString()))
                .withHeader("X-Api-Key", equalTo("test-api-key"))
                .withRequestBody(matchingJsonPath("$.endToEndId", equalTo(saved.getEndToEndId())))
                .withRequestBody(matchingJsonPath("$.token", equalTo("tok_happy")))
                .withRequestBody(matchingJsonPath("$.amount.value", equalTo("125.50")))
                .withRequestBody(matchingJsonPath("$.amount.currency", equalTo("GBP")))
                .withRequestBody(matchingJsonPath("$.callbackUrl",
                        equalTo("http://payments-api:8080/webhooks/meridian"))));
    }

    @Test
    void theStoredPaymentCanBeReadBack() {
        stubAccepted();
        ResponseEntity<JsonNode> created = submit(newKey(), body("tok_get", "10.00", "EUR"));

        ResponseEntity<JsonNode> fetched = get("/api/payments/" + created.getBody().get("id").asString());

        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(fetched.getBody()).isEqualTo(created.getBody());
    }

    @Test
    void paymentsInDifferentCurrenciesKeepTheirOwnCurrency() {
        stubAccepted();
        JsonNode gbp = submit(newKey(), body("tok_a", "20.00", "GBP")).getBody();
        JsonNode eur = submit(newKey(), body("tok_b", "20.00", "EUR")).getBody();

        assertThat(get("/api/payments/" + gbp.get("id").asString()).getBody()
                .get("amount").get("currency").asString()).isEqualTo("GBP");
        assertThat(get("/api/payments/" + eur.get("id").asString()).getBody()
                .get("amount").get("currency").asString()).isEqualTo("EUR");
    }

    // ---- idempotency ------------------------------------------------------

    @Test
    void sameKeyAndBodyReturnsTheSamePaymentWithOneMeridianCall() {
        stubAccepted();
        String key = newKey();
        String json = body("tok_same", "50.00", "GBP");

        ResponseEntity<JsonNode> first = submit(key, json);
        ResponseEntity<JsonNode> second = submit(key, json);

        assertThat(second.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(meridianRequests()).hasSize(1);
    }

    @Test
    void sameKeyWithADifferentBodyIsAConflict() {
        stubAccepted();
        String key = newKey();
        submit(key, body("tok_conf", "50.00", "GBP"));

        ResponseEntity<JsonNode> conflict = submit(key, body("tok_conf", "51.00", "GBP"));

        assertProblem(conflict, 409, "IDEMPOTENCY_KEY_CONFLICT");
        assertThat(meridianRequests()).hasSize(1);
    }

    @Test
    void twoSimultaneousRequestsWithTheSameKeyCreateOnePayment() throws Exception {
        meridian.stubFor(post("/payments").willReturn(aResponse()
                .withStatus(202).withFixedDelay(300)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"paymentId\":\"pay_abc123\"}")));
        String key = newKey();
        String json = body("tok_race", "30.00", "GBP");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ResponseEntity<JsonNode>> call = () -> submit(key, json);
            Future<ResponseEntity<JsonNode>> a = pool.submit(call);
            Future<ResponseEntity<JsonNode>> b = pool.submit(call);

            ResponseEntity<JsonNode> first = a.get();
            ResponseEntity<JsonNode> second = b.get();

            assertThat(first.getStatusCode().value()).isEqualTo(202);
            assertThat(second.getStatusCode().value()).isEqualTo(202);
            assertThat(first.getBody().get("id")).isEqualTo(second.getBody().get("id"));
            assertThat(payments.findAll().stream().filter(p -> key.equals(p.getIdempotencyKey())).count())
                    .isEqualTo(1);
            // If Meridian was called twice, it was with the same key, so it counts as one request.
            assertThat(meridianRequests())
                    .allSatisfy(r -> assertThat(r.getHeader("Idempotency-Key"))
                            .isEqualTo(first.getBody().get("id").asString()));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Meridian failures ------------------------------------------------

    @Test
    void aTemporaryMeridianErrorIsRetriedWithTheSameKeyAndCreatesOnePayment() {
        meridian.stubFor(post("/payments").inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError())
                .willSetStateTo("recovered"));
        meridian.stubFor(post("/payments").inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentId\":\"pay_abc123\"}")));
        String key = newKey();

        ResponseEntity<JsonNode> response = submit(key, body("tok_flaky", "40.00", "GBP"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        String paymentId = response.getBody().get("id").asString();
        assertThat(meridianRequests()).hasSize(2)
                .allSatisfy(r -> assertThat(r.getHeader("Idempotency-Key")).isEqualTo(paymentId));
        assertThat(payments.findByIdempotencyKey(key).orElseThrow().getProviderPaymentId())
                .isEqualTo("pay_abc123");
    }

    @Test
    void whenMeridianIsDownThePaymentStaysPendingAndRepeatingTheRequestResubmitsIt() {
        meridian.stubFor(post("/payments").willReturn(aResponse().withStatus(503)));
        String key = newKey();
        String json = body("tok_down", "60.00", "GBP");

        ResponseEntity<JsonNode> failed = submit(key, json);

        assertProblem(failed, 502, "PROVIDER_UNAVAILABLE");
        Payment stuck = payments.findByIdempotencyKey(key).orElseThrow();
        assertThat(stuck.getStatus()).isEqualTo(PaymentStatus.PDNG);
        assertThat(stuck.getProviderPaymentId()).isNull();
        assertThat(meridianRequests()).hasSize(3);

        // Meridian recovers; the caller repeats exactly the same request.
        meridian.resetMappings();
        stubAccepted();
        ResponseEntity<JsonNode> retry = submit(key, json);

        assertThat(retry.getStatusCode().value()).isEqualTo(202);
        assertThat(retry.getBody().get("id").asString()).isEqualTo(stuck.getId().toString());
        assertThat(payments.findByIdempotencyKey(key).orElseThrow().getProviderPaymentId())
                .isEqualTo("pay_abc123");
        // All four attempts used the same provider key, so Meridian could never charge twice.
        assertThat(meridianRequests()).hasSize(4)
                .allSatisfy(r -> assertThat(r.getHeader("Idempotency-Key")).isEqualTo(stuck.getId().toString()));
    }

    @Test
    void aRejectionByMeridianMarksThePaymentRejected() {
        meridian.stubFor(post("/payments").willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"VALIDATION_ERROR\",\"message\":\"token expired\"}")));
        String key = newKey();

        ResponseEntity<JsonNode> response = submit(key, body("tok_expired", "70.00", "GBP"));

        assertProblem(response, 422, "PROVIDER_REJECTED");
        Payment saved = payments.findByIdempotencyKey(key).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.RJCT);
        assertThat(saved.getStatusReason()).isEqualTo(DeclineReason.NARR);
        assertThat(meridianRequests()).hasSize(1);
    }

    // ---- validation and rules ---------------------------------------------

    @Test
    void aCurrencyMeridianCannotHandleIsRefusedBeforeAnythingIsSaved() {
        String key = newKey();

        ResponseEntity<JsonNode> response = submit(key, body("tok_jpy", "500.00", "JPY"));

        assertProblem(response, 422, "CURRENCY_NOT_SUPPORTED");
        assertThat(payments.findByIdempotencyKey(key)).isEmpty();
        assertThat(meridianRequests()).isEmpty();
    }

    @Test
    void aCurrencyOutsideTheAllowlistIsRefused() {
        assertProblem(submit(newKey(), body("tok_usd", "5.00", "USD")), 422, "CURRENCY_NOT_SUPPORTED");
    }

    @Test
    void invalidAmountsAreRefused() {
        assertProblem(submit(newKey(), body("tok_zero", "0.00", "GBP")), 422, "INVALID_AMOUNT");
        assertProblem(submit(newKey(), body("tok_big", "10000.01", "GBP")), 422, "INVALID_AMOUNT");
        assertThat(meridianRequests()).isEmpty();
    }

    @Test
    void malformedRequestsAreValidationErrors() {
        assertProblem(submit(null, body("tok_x", "5.00", "GBP")), 400, "VALIDATION_ERROR");
        assertProblem(submit("k".repeat(65), body("tok_x", "5.00", "GBP")), 400, "VALIDATION_ERROR");
        assertProblem(submit(newKey(), body("tok_x", "5.5", "GBP")), 400, "VALIDATION_ERROR");
        assertProblem(submit(newKey(), body("tok_x", "5.00", "gbp")), 400, "VALIDATION_ERROR");
        assertProblem(submit(newKey(), body("", "5.00", "GBP")), 400, "VALIDATION_ERROR");
        assertProblem(submit(newKey(), "{\"amount\":{\"value\":\"5.00\",\"currency\":\"GBP\"}}"), 400, "VALIDATION_ERROR");
        assertProblem(submit(newKey(), "not json"), 400, "VALIDATION_ERROR");
        assertThat(meridianRequests()).isEmpty();
    }

    // ---- read endpoints ---------------------------------------------------

    @Test
    void unknownPaymentIsNotFound() {
        assertProblem(get("/api/payments/" + UUID.randomUUID()), 404, "PAYMENT_NOT_FOUND");
        assertProblem(get("/api/payments/not-a-uuid"), 404, "PAYMENT_NOT_FOUND");
    }

    @Test
    void currenciesListsOnlyWhatCanReallyBeUsed() {
        ResponseEntity<JsonNode> response = get("/api/currencies");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).get("code").asString()).isEqualTo("EUR");
        assertThat(response.getBody().get(1).get("code").asString()).isEqualTo("GBP");
        assertThat(response.getBody().get(1).get("fractionDigits").asInt()).isEqualTo(2);
    }
}
