package com.example.payments.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payments.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** The browser page on another origin can call /api/**, and nobody else can. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "payments.cors.allowed-origin=http://localhost:3000"
        })
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestRestTemplate
class CorsTest {

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<String> preflight(String path, String origin, String method) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", origin);
        headers.set("Access-Control-Request-Method", method);
        headers.set("Access-Control-Request-Headers", "content-type,idempotency-key");
        return rest.exchange(path, HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }

    @Test
    void thePreflightForSubmitIsAllowedForTheUiOrigin() {
        ResponseEntity<String> response = preflight("/api/payments", "http://localhost:3000", "POST");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Headers").toLowerCase())
                .contains("idempotency-key");
    }

    @Test
    void anotherOriginIsRefused() {
        ResponseEntity<String> response = preflight("/api/payments", "http://evil.example", "POST");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void readsCarryTheHeaderToo() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:3000");

        ResponseEntity<String> response = rest.exchange(
                "/api/currencies", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
    }

    @Test
    void theWebhookEndpointHasNoCorsHeaders() {
        ResponseEntity<String> response = preflight("/webhooks/meridian", "http://localhost:3000", "POST");

        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }
}
