package com.meridian.payments.intake.provider.quetzal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.meridian.payments.intake.canonical.EventSchemaValidator;
import com.meridian.payments.intake.canonical.PaymentStatus;
import com.meridian.payments.intake.canonical.PremiumPaymentEvent;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.MappingResult.Unmappable;
import com.meridian.payments.intake.provider.QuarantineReason;
import com.meridian.payments.intake.support.Samples;

class QuetzalPayloadMapperTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-28T18:00:03Z");
    private static final String CORRELATION = "2b9e4f61-8c3a-4d7e-b1f0-6a5c2d9e8f13";

    private final QuetzalPayloadMapper mapper = new QuetzalPayloadMapper();
    private final EventSchemaValidator schema = new EventSchemaValidator();

    @Test
    void chargeSucceededBecomesCapturedInMajorUnits() {
        PremiumPaymentEvent event = mapped(Samples.quetzal("01-charge-succeeded.json"));

        assertThat(event.eventId()).isEqualTo("QUETZAL-ch_7Hk2Lm9QpZ:CAPTURED");
        assertThat(event.providerEventId()).isEqualTo("evt_01JDQ3K8M2X7");
        assertThat(event.policyNumber()).isEqualTo("POL-MX-000412785");
        assertThat(event.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(event.amount()).isEqualTo("1250.00");          // 125000 centavos
        assertThat(event.currency()).isEqualTo("MXN");            // "mxn" upper-cased
        assertThat(event.statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795888796L)); // paid_at, not created
        assertThat(schema.validate(event)).isEmpty();
    }

    @Test
    void chargeFailedUsesTheEventTimeBecauseThereIsNoChargeTimestamp() {
        PremiumPaymentEvent event = mapped(Samples.quetzal("02-charge-failed.json"));

        assertThat(event.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(event.amount()).isEqualTo("980.00");
        assertThat(event.statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795889400L));
        assertThat(schema.validate(event)).isEmpty();
    }

    @Test
    void chargeRefundedCarriesTheCumulativeRefundInItsKey() {
        PremiumPaymentEvent event = mapped(Samples.quetzal("03-charge-refunded.json"));

        assertThat(event.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(event.amountRefunded()).isEqualTo("1250.00");
        assertThat(event.eventId()).isEqualTo("QUETZAL-ch_7Hk2Lm9QpZ:REFUNDED:1250.00");
        assertThat(event.paymentKey()).isEqualTo(mapped(Samples.quetzal("01-charge-succeeded.json")).paymentKey());
        assertThat(event.statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795975195L)); // refunded_at
        assertThat(schema.validate(event)).isEmpty();
    }

    @Test
    void aSecondPartialRefundIsADifferentBusinessEvent() {
        String sample = sample("03-charge-refunded.json");
        PremiumPaymentEvent partial = mapped(sample.replace("\"amount_refunded\": 125000", "\"amount_refunded\": 50000"));
        PremiumPaymentEvent full = mapped(sample);

        assertThat(partial.eventId()).isEqualTo("QUETZAL-ch_7Hk2Lm9QpZ:REFUNDED:500.00");
        assertThat(partial.eventId()).isNotEqualTo(full.eventId());
    }

    @Test
    void unknownEventTypeIsQuarantinedNotGuessed() {
        Unmappable result = unmappable(sample("01-charge-succeeded.json")
                .replace("charge.succeeded", "charge.disputed"));

        assertThat(result.reason()).isEqualTo(QuarantineReason.UNKNOWN_PROVIDER_STATUS);
        assertThat(result.providerEventId()).isEqualTo("evt_01JDQ3K8M2X7");
        assertThat(result.providerTransactionId()).isEqualTo("ch_7Hk2Lm9QpZ");
    }

    @Test
    void quarantinedPayloadKeepsNoPayerDataNorPaymentToken() {
        Unmappable result = unmappable(sample("01-charge-succeeded.json")
                .replace("charge.succeeded", "charge.disputed"));

        String kept = result.sanitizedPayload().toString();
        assertThat(kept).doesNotContain("maria.lopez@example.com", "customer", "tok_qz_9sD2fK", "payment_method");
        assertThat(result.sanitizedPayload().at("/data/charge/reference").asText()).isEqualTo("POL-MX-000412785");
    }

    @Test
    void referenceThatIsNotAMexicanPolicyIsQuarantined() {
        Unmappable result = unmappable(sample("01-charge-succeeded.json")
                .replace("POL-MX-000412785", "POL-CO-000412785"));

        assertThat(result.reason()).isEqualTo(QuarantineReason.INVALID_POLICY_REFERENCE);
    }

    @Test
    void amountWithDecimalsInsteadOfCentavosIsInvalid() {
        Unmappable result = unmappable(sample("01-charge-succeeded.json")
                .replace("\"amount\": 125000", "\"amount\": 1250.00"));

        assertThat(result.reason()).isEqualTo(QuarantineReason.INVALID_PAYLOAD);
        assertThat(result.detail()).contains("/data/charge/amount");
    }

    @Test
    void currencyOtherThanMxnIsInvalid() {
        Unmappable result = unmappable(sample("01-charge-succeeded.json").replace("\"mxn\"", "\"usd\""));

        assertThat(result.reason()).isEqualTo(QuarantineReason.INVALID_PAYLOAD);
    }

    @Test
    void bodyThatIsNotJsonIsInvalidAndKeepsNothing() {
        Unmappable result = unmappable("not json");

        assertThat(result.reason()).isEqualTo(QuarantineReason.INVALID_PAYLOAD);
        assertThat(result.sanitizedPayload()).isNull();
    }

    private static String sample(String file) {
        return new String(Samples.quetzal(file), StandardCharsets.UTF_8);
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
