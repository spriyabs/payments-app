package com.example.payments.meridian.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.config.MeridianProperties;
import com.example.payments.meridian.exception.ProviderConflictException;
import com.example.payments.meridian.exception.ProviderRejectedException;
import com.example.payments.meridian.exception.ProviderUnavailableException;
import com.example.payments.meridian.model.ProviderAmount;
import com.example.payments.meridian.model.ProviderPayment;
import com.example.payments.meridian.model.ProviderStatus;
import com.example.payments.meridian.model.ProviderSubmission;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The client against a fake Meridian: request shape, retries with a stable key, and failure mapping. */
class RestClientMeridianClientTest {

    @RegisterExtension
    static WireMockExtension meridian = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String ACCEPTED_BODY = "{\"paymentId\":\"pay_abc123\",\"status\":\"PDNG\"}";

    @BeforeEach
    void reset() {
        meridian.resetAll();
    }

    private RestClientMeridianClient client(String baseUrl, int maxAttempts, Duration readTimeout) {
        return new RestClientMeridianClient(new MeridianProperties(
                baseUrl, "test-key", "secret", "http://payments-api:8080/webhooks/meridian",
                Duration.ofSeconds(1), readTimeout, maxAttempts, Duration.ofMillis(10)));
    }

    private RestClientMeridianClient client(int maxAttempts) {
        return client(meridian.baseUrl(), maxAttempts, Duration.ofSeconds(2));
    }

    private ProviderSubmission submission() {
        return new ProviderSubmission(
                "e2e0123456789012345678901234567", new ProviderAmount("125.50", "GBP"),
                "tok_secret", "http://payments-api:8080/webhooks/meridian");
    }

    private void stubAccepted() {
        meridian.stubFor(post("/payments").willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody(ACCEPTED_BODY)));
    }

    @Test
    void sendsTheRequestMeridianExpectsAndReturnsItsPaymentId() {
        stubAccepted();

        ProviderPayment result = client(3).submitPayment(submission(), "key-1");

        assertThat(result.paymentId()).isEqualTo("pay_abc123");
        meridian.verify(1, postRequestedFor(urlEqualTo("/payments"))
                .withHeader("Idempotency-Key", equalTo("key-1"))
                .withHeader("X-Api-Key", equalTo("test-key"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.endToEndId", equalTo("e2e0123456789012345678901234567")))
                .withRequestBody(matchingJsonPath("$.amount.value", equalTo("125.50")))
                .withRequestBody(matchingJsonPath("$.amount.currency", equalTo("GBP")))
                .withRequestBody(matchingJsonPath("$.token", equalTo("tok_secret")))
                .withRequestBody(matchingJsonPath("$.callbackUrl",
                        equalTo("http://payments-api:8080/webhooks/meridian"))));
    }

    @Test
    void retriesAfterAServerErrorWithTheSameIdempotencyKey() {
        meridian.stubFor(post("/payments").inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError())
                .willSetStateTo("recovered"));
        meridian.stubFor(post("/payments").inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json").withBody(ACCEPTED_BODY)));

        ProviderPayment result = client(3).submitPayment(submission(), "key-2");

        assertThat(result.paymentId()).isEqualTo("pay_abc123");
        List<LoggedRequest> requests = meridian.findAll(postRequestedFor(urlEqualTo("/payments")));
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(r -> assertThat(r.getHeader("Idempotency-Key")).isEqualTo("key-2"));
    }

    @Test
    void givesUpAfterTheConfiguredNumberOfAttempts() {
        meridian.stubFor(post("/payments").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client(3).submitPayment(submission(), "key-3"))
                .isInstanceOf(ProviderUnavailableException.class);
        meridian.verify(3, postRequestedFor(urlEqualTo("/payments")));
    }

    @Test
    void aSlowResponseIsTreatedAsUnavailableAndRetried() {
        meridian.stubFor(post("/payments").willReturn(aResponse()
                .withStatus(202).withFixedDelay(1500).withBody(ACCEPTED_BODY)));
        RestClientMeridianClient slowClient = client(meridian.baseUrl(), 2, Duration.ofMillis(300));

        assertThatThrownBy(() -> slowClient.submitPayment(submission(), "key-4"))
                .isInstanceOf(ProviderUnavailableException.class);
        meridian.verify(2, postRequestedFor(urlEqualTo("/payments")));
    }

    @Test
    void anUnreachableMeridianIsUnavailable() {
        // Nothing listens on port 1.
        RestClientMeridianClient unreachable = client("http://localhost:1", 2, Duration.ofSeconds(1));

        assertThatThrownBy(() -> unreachable.submitPayment(submission(), "key-5"))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void aRejectionIsNotRetried() {
        meridian.stubFor(post("/payments").willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"VALIDATION_ERROR\",\"message\":\"token expired\"}")));

        assertThatThrownBy(() -> client(3).submitPayment(submission(), "key-6"))
                .isInstanceOf(ProviderRejectedException.class);
        meridian.verify(1, postRequestedFor(urlEqualTo("/payments")));
    }

    @Test
    void badCredentialsAreNotRetriedAndAreReportedAsUnavailable() {
        meridian.stubFor(post("/payments").willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client(3).submitPayment(submission(), "key-7"))
                .isInstanceOf(ProviderUnavailableException.class);
        meridian.verify(1, postRequestedFor(urlEqualTo("/payments")));
    }

    @Test
    void anAnswerWithoutAPaymentIdIsTreatedAsAFailure() {
        meridian.stubFor(post("/payments").willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json").withBody("{}")));

        assertThatThrownBy(() -> client(2).submitPayment(submission(), "key-8"))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void theSubmissionNeverPrintsTheToken() {
        assertThat(submission().toString()).doesNotContain("tok_secret");
    }

    @Test
    void cancelPostsToTheProviderPaymentWithTheGivenKey() {
        meridian.stubFor(post("/payments/pay_abc123/cancellation").willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")));

        client(3).cancelPayment("pay_abc123", "cancel-1");

        meridian.verify(1, postRequestedFor(urlEqualTo("/payments/pay_abc123/cancellation"))
                .withHeader("Idempotency-Key", equalTo("cancel-1"))
                .withHeader("X-Api-Key", equalTo("test-key")));
    }

    @Test
    void cancelRetriesServerErrorsWithTheSameKey() {
        meridian.stubFor(post("/payments/pay_abc123/cancellation").inScenario("flaky-cancel")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(serverError()).willSetStateTo("ok"));
        meridian.stubFor(post("/payments/pay_abc123/cancellation").inScenario("flaky-cancel")
                .whenScenarioStateIs("ok").willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        client(3).cancelPayment("pay_abc123", "cancel-2");

        List<LoggedRequest> requests = meridian.findAll(postRequestedFor(urlEqualTo("/payments/pay_abc123/cancellation")));
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(r -> assertThat(r.getHeader("Idempotency-Key")).isEqualTo("cancel-2"));
    }

    @Test
    void cancelConflictIsReportedAndNotRetried() {
        meridian.stubFor(post("/payments/pay_abc123/cancellation").willReturn(aResponse().withStatus(409)));

        assertThatThrownBy(() -> client(3).cancelPayment("pay_abc123", "cancel-3"))
                .isInstanceOf(ProviderConflictException.class);
        meridian.verify(1, postRequestedFor(urlEqualTo("/payments/pay_abc123/cancellation")));
    }

    @Test
    void cancelOfAnUnknownPaymentIsRejected() {
        meridian.stubFor(post("/payments/pay_abc123/cancellation").willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client(3).cancelPayment("pay_abc123", "cancel-4"))
                .isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void getPaymentReturnsTheStatusAndReason() {
        meridian.stubFor(get("/payments/pay_abc123").willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"paymentId\":\"pay_abc123\",\"status\":\"RJCT\",\"statusReason\":\"AM04\"}")));

        ProviderStatus status = client(3).getPayment("pay_abc123");

        assertThat(status.status()).isEqualTo("RJCT");
        assertThat(status.statusReason()).isEqualTo("AM04");
    }

    @Test
    void getPaymentGivesUpAfterTheConfiguredAttempts() {
        meridian.stubFor(get("/payments/pay_abc123").willReturn(serverError()));

        assertThatThrownBy(() -> client(2).getPayment("pay_abc123"))
                .isInstanceOf(ProviderUnavailableException.class);
    }
}
