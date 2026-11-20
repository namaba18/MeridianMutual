package com.meridian.payments.intake.provider.quetzal;

import static com.meridian.payments.intake.support.Samples.QUETZAL_TEST_SECRET;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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

/** Uses the official test vectors of reference/providers/quetzal-mx/WEBHOOK_SPEC.md. */
class QuetzalSignatureVerifierTest {

    private static final long T = 1795888803L;
    private static final String SIG_01 = "2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031";
    private static final byte[] BODY_01 = Samples.quetzal("01-charge-succeeded.json");

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "01-charge-succeeded.json, 1795888803, 2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031",
            "02-charge-failed.json,    1795889403, d793d3da0a8d4d66e984ada3daa8b9f473a0eef153d99cdd83bb70f60f13ccc9",
            "03-charge-refunded.json,  1795975203, a61f9d88d861cd4cc63359b5ca6e6d11d55369db7079387dee9a5024ccb2cae6"
    })
    void acceptsEveryOfficialTestVector(String file, long t, String v1) {
        SignatureCheck check = verifierAt(t).verify(request(Samples.quetzal(file), "t=" + t + ",v1=" + v1));

        assertThat(check.valid()).isTrue();
    }

    @Test
    void rejectsABodyChangedByOneByte() {
        byte[] tampered = BODY_01.clone();
        tampered[tampered.length - 2] ^= 1;

        assertThat(verifierAt(T).verify(request(tampered, header(SIG_01))).failure())
                .isEqualTo(Failure.SIGNATURE_MISMATCH);
    }

    @Test
    void rejectsTheSameJsonReserializedOrWithWindowsLineEndings() {
        // The signature covers the exact bytes. Re-serializing the JSON or converting line endings
        // (as git core.autocrlf did to these samples) breaks it, so the body is never re-encoded.
        byte[] crlf = new String(BODY_01, StandardCharsets.UTF_8).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

        assertThat(verifierAt(T).verify(request(crlf, header(SIG_01))).failure())
                .isEqualTo(Failure.SIGNATURE_MISMATCH);
    }

    @Test
    void rejectsASignatureMadeWithAnotherSecret() {
        QuetzalSignatureVerifier verifier = new QuetzalSignatureVerifier(
                Samples.secrets(Provider.QUETZAL, "qz_test_whsec_WRONG"), clockAt(T));

        assertThat(verifier.verify(request(BODY_01, header(SIG_01))).failure())
                .isEqualTo(Failure.SIGNATURE_MISMATCH);
    }

    @Test
    void acceptsTheBoundaryOfTheWindowAndRejectsOneSecondLater() {
        assertThat(verifierAt(T + 300).verify(request(BODY_01, header(SIG_01))).valid()).isTrue();
        assertThat(verifierAt(T - 300).verify(request(BODY_01, header(SIG_01))).valid()).isTrue();

        assertThat(verifierAt(T + 301).verify(request(BODY_01, header(SIG_01))).failure())
                .isEqualTo(Failure.TIMESTAMP_OUT_OF_WINDOW);
        assertThat(verifierAt(T - 301).verify(request(BODY_01, header(SIG_01))).failure())
                .isEqualTo(Failure.TIMESTAMP_OUT_OF_WINDOW);
    }

    @Test
    void secretRotationHeaderWithTwoSignaturesIsValidIfAnyMatches() {
        String header = "t=" + T + ",v1=" + "0".repeat(64) + ",v1=" + SIG_01;

        assertThat(verifierAt(T).verify(request(BODY_01, header)).valid()).isTrue();
    }

    @Test
    void secretRotationOnOurSideAcceptsTheNextSecretWhileTheOldOneIsStillActive() {
        QuetzalSignatureVerifier verifier = new QuetzalSignatureVerifier(
                Samples.secrets(Provider.QUETZAL, "qz_old_secret_being_retired", QUETZAL_TEST_SECRET), clockAt(T));

        assertThat(verifier.verify(request(BODY_01, header(SIG_01))).valid()).isTrue();
    }

    @Test
    void unknownSignatureSchemesAreIgnored() {
        String header = "t=" + T + ",v0=deadbeef,v1=" + SIG_01;

        assertThat(verifierAt(T).verify(request(BODY_01, header)).valid()).isTrue();
    }

    @Test
    void missingHeaderIsRejected() {
        WebhookRequest noHeader = new WebhookRequest(BODY_01, Map.of());

        assertThat(verifierAt(T).verify(noHeader).failure()).isEqualTo(Failure.MISSING_HEADER);
    }

    @Test
    void headerNameIsCaseInsensitive() {
        WebhookRequest lowerCase = new WebhookRequest(BODY_01, Map.of("quetzal-signature", header(SIG_01)));

        assertThat(verifierAt(T).verify(lowerCase).valid()).isTrue();
    }

    @Test
    void malformedHeadersAreRejected() {
        QuetzalSignatureVerifier verifier = verifierAt(T);

        assertThat(verifier.verify(request(BODY_01, "v1=" + SIG_01)).failure())
                .as("no timestamp").isEqualTo(Failure.MALFORMED_HEADER);
        assertThat(verifier.verify(request(BODY_01, "t=" + T)).failure())
                .as("no v1").isEqualTo(Failure.MALFORMED_HEADER);
        assertThat(verifier.verify(request(BODY_01, "t=" + T + ",t=" + T + ",v1=" + SIG_01)).failure())
                .as("duplicate t").isEqualTo(Failure.MALFORMED_HEADER);
        assertThat(verifier.verify(request(BODY_01, "t=abc,v1=" + SIG_01)).failure())
                .as("non-numeric t").isEqualTo(Failure.MALFORMED_HEADER);
        assertThat(verifier.verify(request(BODY_01, "garbage")).failure())
                .as("garbage").isEqualTo(Failure.MALFORMED_HEADER);
    }

    @Test
    void noConfiguredSecretIsReportedAsADeploymentError() {
        QuetzalSignatureVerifier verifier = new QuetzalSignatureVerifier(Samples.secrets(Provider.ANDINA, "x"), clockAt(T));

        assertThat(verifier.verify(request(BODY_01, header(SIG_01))).failure())
                .isEqualTo(Failure.NO_SECRET_CONFIGURED);
    }

    private static String header(String v1) {
        return "t=" + T + ",v1=" + v1;
    }

    private static WebhookRequest request(byte[] body, String signatureHeader) {
        return new WebhookRequest(body, Map.of("Quetzal-Signature", signatureHeader));
    }

    private static QuetzalSignatureVerifier verifierAt(long epochSecond) {
        return new QuetzalSignatureVerifier(Samples.secrets(Provider.QUETZAL, QUETZAL_TEST_SECRET), clockAt(epochSecond));
    }

    private static Clock clockAt(long epochSecond) {
        return Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
    }
}
