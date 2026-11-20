package com.meridian.payments.intake.provider.andina;

import static com.meridian.payments.intake.support.Samples.ANDINA_TEST_SECRET;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.provider.SignatureCheck;
import com.meridian.payments.intake.provider.SignatureCheck.Failure;
import com.meridian.payments.intake.provider.WebhookRequest;
import com.meridian.payments.intake.support.Samples;

/** Uses the official test vectors of reference/providers/andina-co/WEBHOOK_SPEC.md. */
class AndinaSignatureVerifierTest {

    private static final String TS_02 = "2026-11-28T14:03:11-05:00";
    private static final String SIG_02 = "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk=";
    private static final byte[] BODY_02 = Samples.andina("02-transaccion-aprobada.json");

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "01-transaccion-pendiente.json,          2026-11-28T14:02:59-05:00, JhaBZfk7eQsoMbbQbThmAx/bCyehBOzKYw8XTP2a40k=",
            "02-transaccion-aprobada.json,           2026-11-28T14:03:11-05:00, MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk=",
            "03-transaccion-aprobada-reintento.json, 2026-11-28T14:08:11-05:00, CNu6m5X6B9bMKTsi/IzZfGquIh/KdxTyesBtL4ax424=",
            "04-transaccion-reversada.json,          2026-12-03T09:15:42-05:00, AfGO+MEffk0AWIEEE3cgyff4hWewoOaDhygCWxs/fNI="
    })
    void acceptsEveryOfficialTestVector(String file, String timestamp, String signature) {
        SignatureCheck check = verifierAt(timestamp).verify(request(Samples.andina(file), timestamp, signature));

        assertThat(check.valid()).isTrue();
    }

    @Test
    void timestampIsSignedExactlyAsReceivedNotReformatted() {
        // Same instant written in UTC: semantically equal, but not the bytes Andina signed.
        String sameInstantInUtc = "2026-11-28T19:03:11Z";

        assertThat(verifierAt(TS_02).verify(request(BODY_02, sameInstantInUtc, SIG_02)).failure())
                .isEqualTo(Failure.SIGNATURE_MISMATCH);
    }

    @Test
    void signatureOfOneNotificationCannotBeReusedForAnother() {
        // The retry (03) carries a new timestamp and body; replaying 02's signature on it must fail.
        byte[] retryBody = Samples.andina("03-transaccion-aprobada-reintento.json");

        assertThat(verifierAt(TS_02).verify(request(retryBody, TS_02, SIG_02)).failure())
                .isEqualTo(Failure.SIGNATURE_MISMATCH);
    }

    @Test
    void rejectsABodyChangedByOneByte() {
        byte[] tampered = BODY_02.clone();
        tampered[tampered.length - 2] ^= 1;

        assertThat(verifierAt(TS_02).verify(request(tampered, TS_02, SIG_02)).failure())
                .isEqualTo(Failure.SIGNATURE_MISMATCH);
    }

    @Test
    void acceptsUpToTenMinutesOfSkewInEitherDirection() {
        OffsetDateTime signedAt = OffsetDateTime.parse(TS_02);

        assertThat(verifierAt(signedAt.plusMinutes(10)).verify(request(BODY_02, TS_02, SIG_02)).valid()).isTrue();
        assertThat(verifierAt(signedAt.minusMinutes(10)).verify(request(BODY_02, TS_02, SIG_02)).valid()).isTrue();

        assertThat(verifierAt(signedAt.plus(Duration.ofMinutes(10).plusSeconds(1)))
                .verify(request(BODY_02, TS_02, SIG_02)).failure())
                .isEqualTo(Failure.TIMESTAMP_OUT_OF_WINDOW);
        assertThat(verifierAt(signedAt.minus(Duration.ofMinutes(10).plusSeconds(1)))
                .verify(request(BODY_02, TS_02, SIG_02)).failure())
                .isEqualTo(Failure.TIMESTAMP_OUT_OF_WINDOW);
    }

    @Test
    void acceptsTheNextSecretDuringARotation() {
        AndinaSignatureVerifier verifier = new AndinaSignatureVerifier(
                Samples.secrets(Provider.ANDINA, "andina_old_secret", ANDINA_TEST_SECRET), clockAt(TS_02));

        assertThat(verifier.verify(request(BODY_02, TS_02, SIG_02)).valid()).isTrue();
    }

    @Test
    void missingHeadersAreRejected() {
        AndinaSignatureVerifier verifier = verifierAt(TS_02);

        assertThat(verifier.verify(new WebhookRequest(BODY_02, Map.of("X-Andina-Signature", SIG_02))).failure())
                .as("no timestamp").isEqualTo(Failure.MISSING_HEADER);
        assertThat(verifier.verify(new WebhookRequest(BODY_02, Map.of("X-Andina-Timestamp", TS_02))).failure())
                .as("no signature").isEqualTo(Failure.MISSING_HEADER);
    }

    @Test
    void malformedHeadersAreRejected() {
        AndinaSignatureVerifier verifier = verifierAt(TS_02);

        assertThat(verifier.verify(request(BODY_02, "28/11/2026 14:03", SIG_02)).failure())
                .as("timestamp not ISO-8601").isEqualTo(Failure.MALFORMED_HEADER);
        assertThat(verifier.verify(request(BODY_02, TS_02, "not base64 !!")).failure())
                .as("signature not Base64").isEqualTo(Failure.MALFORMED_HEADER);
    }

    private static WebhookRequest request(byte[] body, String timestamp, String signature) {
        return new WebhookRequest(body, Map.of("X-Andina-Timestamp", timestamp, "X-Andina-Signature", signature));
    }

    private static AndinaSignatureVerifier verifierAt(String isoTimestamp) {
        return verifierAt(OffsetDateTime.parse(isoTimestamp));
    }

    private static AndinaSignatureVerifier verifierAt(OffsetDateTime now) {
        return new AndinaSignatureVerifier(Samples.secrets(Provider.ANDINA, ANDINA_TEST_SECRET), clockAt(now));
    }

    private static Clock clockAt(String isoTimestamp) {
        return clockAt(OffsetDateTime.parse(isoTimestamp));
    }

    private static Clock clockAt(OffsetDateTime now) {
        return Clock.fixed(now.toInstant(), ZoneOffset.UTC);
    }
}
