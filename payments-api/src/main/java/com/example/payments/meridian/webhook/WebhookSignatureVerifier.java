package com.example.payments.meridian.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Checks the {@code X-Meridian-Signature} header: lowercase hex HMAC-SHA256 of the RAW
 * request body, using the shared webhook secret. Always verify the exact bytes received,
 * never a re-serialised copy, or the signature will not match.
 */
public final class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public WebhookSignatureVerifier(String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** True only if the header is present and matches the body. Compares in constant time. */
    public boolean isValid(byte[] rawBody, String signatureHeader) {
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        byte[] expected = sign(rawBody).getBytes(StandardCharsets.US_ASCII);
        byte[] provided = signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided);
    }

    /** The signature Meridian would send for this body. Also used by tests. */
    public String sign(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
