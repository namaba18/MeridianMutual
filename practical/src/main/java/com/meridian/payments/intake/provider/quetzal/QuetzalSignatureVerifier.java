package com.meridian.payments.intake.provider.quetzal;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Component;

import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.port.SecretProvider;
import com.meridian.payments.intake.provider.Hmac;
import com.meridian.payments.intake.provider.SignatureCheck;
import com.meridian.payments.intake.provider.SignatureCheck.Failure;
import com.meridian.payments.intake.provider.SignatureVerifier;
import com.meridian.payments.intake.provider.WebhookRequest;

/**
 * Quetzal Pagos signature (reference/providers/quetzal-mx/WEBHOOK_SPEC.md):
 * {@code Quetzal-Signature: t=<unix>,v1=<hex>[,v1=<hex>...]}, where each v1 is the lowercase hex
 * HMAC-SHA256 of {@code <t>.<raw body>}. Valid if |now - t| <= 300 s and <b>any</b> v1 matches
 * <b>any</b> active secret (secret rotation). Unknown schemes (v0, v2...) are ignored.
 */
@Component
public class QuetzalSignatureVerifier implements SignatureVerifier {

    static final String HEADER = "Quetzal-Signature";
    static final Duration TOLERANCE = Duration.ofSeconds(300);
    private static final int MAX_HEADER_LENGTH = 512;

    private final SecretProvider secrets;
    private final Clock clock;

    public QuetzalSignatureVerifier(SecretProvider secrets, Clock clock) {
        this.secrets = secrets;
        this.clock = clock;
    }

    @Override
    public Provider provider() {
        return Provider.QUETZAL;
    }

    @Override
    public SignatureCheck verify(WebhookRequest request) {
        String header = request.header(HEADER).orElse(null);
        if (header == null) {
            return SignatureCheck.failed(Failure.MISSING_HEADER);
        }
        ParsedHeader parsed = ParsedHeader.parse(header);
        if (parsed == null) {
            return SignatureCheck.failed(Failure.MALFORMED_HEADER);
        }

        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - parsed.timestamp()) > TOLERANCE.toSeconds()) {
            return SignatureCheck.failed(Failure.TIMESTAMP_OUT_OF_WINDOW);
        }

        List<byte[]> activeSecrets = secrets.webhookSecrets(Provider.QUETZAL);
        if (activeSecrets.isEmpty()) {
            return SignatureCheck.failed(Failure.NO_SECRET_CONFIGURED);
        }

        byte[] body = request.rawBody();
        String signedPrefix = Long.toString(parsed.timestamp());
        for (byte[] secret : activeSecrets) {
            byte[] expected = Hmac.sha256(secret, signedPrefix, body);
            for (byte[] candidate : parsed.v1Signatures()) {
                if (Hmac.matches(expected, candidate)) {
                    return SignatureCheck.ok();
                }
            }
        }
        return SignatureCheck.failed(Failure.SIGNATURE_MISMATCH);
    }

    /** {@code t} exactly once, at least one well-formed {@code v1}. */
    record ParsedHeader(long timestamp, List<byte[]> v1Signatures) {

        static ParsedHeader parse(String header) {
            if (header.length() > MAX_HEADER_LENGTH) {
                return null;
            }
            Long timestamp = null;
            List<byte[]> v1 = new ArrayList<>();
            for (String element : header.split(",")) {
                int eq = element.indexOf('=');
                if (eq <= 0) {
                    return null;
                }
                String key = element.substring(0, eq).trim();
                String value = element.substring(eq + 1).trim();
                switch (key) {
                    case "t" -> {
                        if (timestamp != null || !value.matches("\\d{1,12}")) {
                            return null;
                        }
                        timestamp = Long.parseLong(value);
                    }
                    case "v1" -> {
                        if (value.matches("[0-9a-f]{64}")) {
                            v1.add(HexFormat.of().parseHex(value));
                        }
                    }
                    default -> {
                        // Other schemes are ignored, as the spec allows.
                    }
                }
            }
            return timestamp == null || v1.isEmpty() ? null : new ParsedHeader(timestamp, v1);
        }
    }
}
