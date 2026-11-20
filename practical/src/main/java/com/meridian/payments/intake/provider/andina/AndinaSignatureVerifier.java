package com.meridian.payments.intake.provider.andina;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
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
 * Andina Pay signature, API 2.1+ (reference/providers/andina-co/WEBHOOK_SPEC.md):
 * {@code X-Andina-Signature} is the Base64 HMAC-SHA256 of
 * {@code <X-Andina-Timestamp value>.<raw body>}. The timestamp is signed <b>exactly as
 * received</b> (not re-formatted), and rejected if it differs from now by more than 10 minutes.
 */
@Component
public class AndinaSignatureVerifier implements SignatureVerifier {

    static final String TIMESTAMP_HEADER = "X-Andina-Timestamp";
    static final String SIGNATURE_HEADER = "X-Andina-Signature";
    static final Duration TOLERANCE = Duration.ofMinutes(10);
    private static final int MAX_HEADER_LENGTH = 128;

    private final SecretProvider secrets;
    private final Clock clock;

    public AndinaSignatureVerifier(SecretProvider secrets, Clock clock) {
        this.secrets = secrets;
        this.clock = clock;
    }

    @Override
    public Provider provider() {
        return Provider.ANDINA;
    }

    @Override
    public SignatureCheck verify(WebhookRequest request) {
        String timestamp = request.header(TIMESTAMP_HEADER).orElse(null);
        String signature = request.header(SIGNATURE_HEADER).orElse(null);
        if (timestamp == null || signature == null) {
            return SignatureCheck.failed(Failure.MISSING_HEADER);
        }
        if (timestamp.length() > MAX_HEADER_LENGTH || signature.length() > MAX_HEADER_LENGTH) {
            return SignatureCheck.failed(Failure.MALFORMED_HEADER);
        }

        OffsetDateTime signedAt;
        byte[] provided;
        try {
            signedAt = OffsetDateTime.parse(timestamp);
            provided = Base64.getDecoder().decode(signature);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return SignatureCheck.failed(Failure.MALFORMED_HEADER);
        }

        Duration skew = Duration.between(signedAt.toInstant(), clock.instant()).abs();
        if (skew.compareTo(TOLERANCE) > 0) {
            return SignatureCheck.failed(Failure.TIMESTAMP_OUT_OF_WINDOW);
        }

        List<byte[]> activeSecrets = secrets.webhookSecrets(Provider.ANDINA);
        if (activeSecrets.isEmpty()) {
            return SignatureCheck.failed(Failure.NO_SECRET_CONFIGURED);
        }

        byte[] body = request.rawBody();
        for (byte[] secret : activeSecrets) {
            if (Hmac.matches(Hmac.sha256(secret, timestamp, body), provided)) {
                return SignatureCheck.ok();
            }
        }
        return SignatureCheck.failed(Failure.SIGNATURE_MISMATCH);
    }
}
