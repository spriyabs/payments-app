package com.example.payments.meridian.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    /**
     * Shared test vector: the same secret, body and signature are asserted in
     * meridian-mock/test/mock.test.js, so the mock and the API agree on the format.
     */
    static final String SECRET = "test-secret";
    static final String BODY =
            "{\"eventId\":\"evt_1\",\"paymentId\":\"pay_1\",\"endToEndId\":\"abc\",\"status\":\"ACCP\","
                    + "\"statusReason\":null,\"occurredAt\":\"2026-10-09T10:00:00Z\"}";
    static final String SIGNATURE = "34dd798f9ad93a8edffda420aaa5a6991ebe8e90270688fad43325b48bf42435";

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void signsToTheSharedTestVector() {
        assertThat(verifier.sign(bytes(BODY))).isEqualTo(SIGNATURE);
    }

    @Test
    void acceptsAMatchingSignature() {
        assertThat(verifier.isValid(bytes(BODY), SIGNATURE)).isTrue();
    }

    @Test
    void acceptsUppercaseHexAndSurroundingSpaces() {
        assertThat(verifier.isValid(bytes(BODY), " " + SIGNATURE.toUpperCase() + " ")).isTrue();
    }

    @Test
    void rejectsATamperedBody() {
        assertThat(verifier.isValid(bytes(BODY.replace("ACCP", "ACSC")), SIGNATURE)).isFalse();
    }

    @Test
    void rejectsTheWrongSecret() {
        assertThat(new WebhookSignatureVerifier("other-secret").isValid(bytes(BODY), SIGNATURE)).isFalse();
    }

    @Test
    void rejectsAMissingOrEmptyOrMalformedSignature() {
        assertThat(verifier.isValid(bytes(BODY), null)).isFalse();
        assertThat(verifier.isValid(bytes(BODY), "")).isFalse();
        assertThat(verifier.isValid(bytes(BODY), "   ")).isFalse();
        assertThat(verifier.isValid(bytes(BODY), "not-hex")).isFalse();
        assertThat(verifier.isValid(bytes(BODY), SIGNATURE.substring(1))).isFalse();
    }

    @Test
    void rejectsAMissingBody() {
        assertThat(verifier.isValid(null, SIGNATURE)).isFalse();
    }

    @Test
    void aSignatureForOneBodyDoesNotValidateAnother() {
        assertThat(verifier.isValid(new byte[0], SIGNATURE)).isFalse();
    }
}
