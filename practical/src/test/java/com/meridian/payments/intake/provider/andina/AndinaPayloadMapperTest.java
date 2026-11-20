package com.meridian.payments.intake.provider.andina;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.meridian.payments.intake.canonical.EventSchemaValidator;
import com.meridian.payments.intake.canonical.PaymentStatus;
import com.meridian.payments.intake.canonical.PremiumPaymentEvent;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.MappingResult.Unmappable;
import com.meridian.payments.intake.provider.QuarantineReason;
import com.meridian.payments.intake.support.Samples;

class AndinaPayloadMapperTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-28T19:03:11Z");
    private static final String CORRELATION = "7f3a0c2e-5d1b-4c8e-9a2f-1b6e0d4c3a21";

    private final AndinaPayloadMapper mapper = new AndinaPayloadMapper();
    private final EventSchemaValidator schema = new EventSchemaValidator();

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "01-transaccion-pendiente.json,          PENDING,  ANDINA-TRX-CO-5521907:PENDING,                  2026-11-28T19:02:58Z",
            "02-transaccion-aprobada.json,           CAPTURED, ANDINA-TRX-CO-5521907:CAPTURED,                 2026-11-28T19:03:09Z",
            "03-transaccion-aprobada-reintento.json, CAPTURED, ANDINA-TRX-CO-5521907:CAPTURED,                 2026-11-28T19:03:09Z",
            "04-transaccion-reversada.json,          REFUNDED, ANDINA-TRX-CO-5521907:REFUNDED:185000.00,       2026-12-03T14:15:40Z"
    })
    void everySampleMapsToAValidCanonicalEvent(String file, PaymentStatus status, String eventId, String changedAt) {
        PremiumPaymentEvent event = mapped(Samples.andina(file));

        assertThat(event.status()).isEqualTo(status);
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.statusChangedAt()).isEqualTo(Instant.parse(changedAt)); // offset -05:00 converted to UTC
        assertThat(event.amount()).isEqualTo("185000.00");
        assertThat(event.currency()).isEqualTo("COP");
        assertThat(event.policyNumber()).isEqualTo("POL-CO-000187342");
        assertThat(schema.validate(event)).isEmpty();
    }

    @Test
    void retryWithANewNotificacionIdIsTheSameBusinessEvent() {
        PremiumPaymentEvent original = mapped(Samples.andina("02-transaccion-aprobada.json"));
        PremiumPaymentEvent retry = mapped(Samples.andina("03-transaccion-aprobada-reintento.json"));

        assertThat(retry.providerEventId()).isEqualTo("ntf-c02d9e13").isNotEqualTo(original.providerEventId());
        assertThat(retry.eventId()).isEqualTo(original.eventId());
    }

    @Test
    void reversalIsAFullRefund() {
        PremiumPaymentEvent event = mapped(Samples.andina("04-transaccion-reversada.json"));

        assertThat(event.amountRefunded()).isEqualTo(event.amount());
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "POL-CO-000187342,   POL-CO-000187342",
            "pol-co-000198004,   POL-CO-000198004",   // INC-0412 message 5d71…a8
            "000215560,          POL-CO-000215560",   // INC-0412 message 0c9e…41
            "' POL-CO-000187342 ', POL-CO-000187342"
    })
    void referenciaAsTypedByThePayerIsNormalized(String referencia, String expected) {
        PremiumPaymentEvent event = mapped(withReferencia(referencia));

        assertThat(event.policyNumber()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @CsvSource({"215560", "POL CO 000187342", "POL-MX-000187342", "mi poliza"})
    void referenciaThatCannotBeNormalizedIsQuarantined(String referencia) {
        Unmappable result = unmappable(withReferencia(referencia));

        assertThat(result.reason()).isEqualTo(QuarantineReason.INVALID_POLICY_REFERENCE);
        assertThat(result.providerTransactionId()).isEqualTo("TRX-CO-5521907");
    }

    @Test
    void unknownEstadoIsQuarantinedNotDefaultedToPending() {
        Unmappable result = unmappable(sample("02-transaccion-aprobada.json").replace("APROBADA", "EN_DISPUTA"));

        assertThat(result.reason()).isEqualTo(QuarantineReason.UNKNOWN_PROVIDER_STATUS);
        assertThat(result.providerEventId()).isEqualTo("ntf-8f1c2a77");
    }

    @Test
    void quarantinedPayloadKeepsNoPayerData() {
        Unmappable result = unmappable(sample("02-transaccion-aprobada.json").replace("APROBADA", "EN_DISPUTA"));

        String kept = result.sanitizedPayload().toString();
        assertThat(kept).doesNotContain("pagador", "Laura", "1020304050", "laura.gomez@example.com");
        assertThat(result.sanitizedPayload().at("/transaccion/estado").asText()).isEqualTo("EN_DISPUTA");
    }

    @Test
    void unknownFieldsAreIgnored() {
        // API 2.3 added the optional field transaccion.canal.
        PremiumPaymentEvent event = mapped(sample("02-transaccion-aprobada.json")
                .replace("\"medio_pago\": \"PSE\",", "\"medio_pago\": \"PSE\", \"canal\": \"APP\", \"nuevo_campo\": 1,"));

        assertThat(event.status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void amountWithMoreThanTwoDecimalsIsInvalid() {
        Unmappable result = unmappable(sample("02-transaccion-aprobada.json").replace("185000.00", "185000.005"));

        assertThat(result.reason()).isEqualTo(QuarantineReason.INVALID_PAYLOAD);
    }

    @Test
    void missingTransactionTimeIsInvalid() {
        Unmappable result = unmappable(sample("02-transaccion-aprobada.json")
                .replace("\"fecha_transaccion\": \"2026-11-28T14:03:09-05:00\",", ""));

        assertThat(result.reason()).isEqualTo(QuarantineReason.INVALID_PAYLOAD);
        assertThat(result.detail()).contains("fecha_transaccion");
    }

    private static String sample(String file) {
        return new String(Samples.andina(file), StandardCharsets.UTF_8);
    }

    private static String withReferencia(String referencia) {
        return sample("02-transaccion-aprobada.json").replace("\"POL-CO-000187342\"", "\"" + referencia + "\"");
    }

    private PremiumPaymentEvent mapped(String body) {
        return mapped(body.getBytes(StandardCharsets.UTF_8));
    }

    private PremiumPaymentEvent mapped(byte[] body) {
        MappingResult result = mapper.map(body, RECEIVED, CORRELATION);
        assertThat(result).isInstanceOf(MappingResult.Mapped.class);
        return ((MappingResult.Mapped) result).event();
    }

    private Unmappable unmappable(String body) {
        MappingResult result = mapper.map(body.getBytes(StandardCharsets.UTF_8), RECEIVED, CORRELATION);
        assertThat(result).isInstanceOf(Unmappable.class);
        return (Unmappable) result;
    }
}
