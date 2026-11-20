package com.meridian.payments.intake.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class PremiumPaymentEventTest {

    private static final EventSchemaValidator SCHEMA = new EventSchemaValidator();

    /** The Andina sample 02-transaccion-aprobada.json, as in SOLUTION_DESIGN §3.2. */
    private static PremiumPaymentEvent.Builder andinaApproved() {
        return PremiumPaymentEvent.builder()
                .provider(Provider.ANDINA)
                .providerTransactionId("TRX-CO-5521907")
                .providerEventId("ntf-8f1c2a77")
                .policyNumber("POL-CO-000187342")
                .status(PaymentStatus.CAPTURED)
                .amount(new BigDecimal("185000.00"))
                .statusChangedAt(Instant.parse("2026-11-28T19:03:09Z"))
                .receivedAt(Instant.parse("2026-11-28T19:03:11Z"))
                .correlationId("7f3a0c2e-5d1b-4c8e-9a2f-1b6e0d4c3a21");
    }

    @Test
    void buildsTheDesignExampleAndItIsValidAgainstTheSchema() throws Exception {
        PremiumPaymentEvent event = andinaApproved().build();

        JsonNode json = CanonicalJson.mapper().valueToTree(event);
        JsonNode expected = CanonicalJson.mapper().readTree("""
                {
                  "schemaVersion": "1.0",
                  "eventId": "ANDINA-TRX-CO-5521907:CAPTURED",
                  "paymentKey": "ANDINA-TRX-CO-5521907",
                  "country": "CO",
                  "provider": "Andina",
                  "providerTransactionId": "TRX-CO-5521907",
                  "providerEventId": "ntf-8f1c2a77",
                  "policyNumber": "POL-CO-000187342",
                  "status": "CAPTURED",
                  "amount": "185000.00",
                  "currency": "COP",
                  "statusChangedAt": "2026-11-28T19:03:09Z",
                  "receivedAt": "2026-11-28T19:03:11Z",
                  "correlationId": "7f3a0c2e-5d1b-4c8e-9a2f-1b6e0d4c3a21"
                }
                """);

        assertThat(json).isEqualTo(expected);
        assertThat(SCHEMA.validate(event)).isEmpty();
    }

    @Test
    void retryWithANewProviderEventIdKeepsTheSameEventId() {
        PremiumPaymentEvent first = andinaApproved().build();
        PremiumPaymentEvent retry = andinaApproved()
                .providerEventId("ntf-c02d9e13") // 03-transaccion-aprobada-reintento.json
                .receivedAt(Instant.parse("2026-11-28T19:08:11Z"))
                .build();

        assertThat(retry.eventId()).isEqualTo(first.eventId());
        assertThat(retry.providerEventId()).isNotEqualTo(first.providerEventId());
    }

    @Nested
    class Refunds {

        @Test
        void refundKeyIncludesTheCumulativeAmount() {
            PremiumPaymentEvent partial = quetzalRefund("500.00");
            PremiumPaymentEvent full = quetzalRefund("1250.00");

            assertThat(partial.eventId()).isEqualTo("QUETZAL-ch_7Hk2Lm9QpZ:REFUNDED:500.00");
            assertThat(full.eventId()).isEqualTo("QUETZAL-ch_7Hk2Lm9QpZ:REFUNDED:1250.00");
            assertThat(partial.paymentKey()).isEqualTo(full.paymentKey());
            assertThat(SCHEMA.validate(partial)).isEmpty();
        }

        @Test
        void refundWithoutAmountRefundedIsRejected() {
            assertThatThrownBy(() -> andinaApproved().status(PaymentStatus.REFUNDED).build())
                    .isInstanceOf(InvalidCanonicalEventException.class)
                    .hasMessageContaining("amountRefunded");
        }

        @Test
        void amountRefundedOnANonRefundIsRejected() {
            assertThatThrownBy(() -> andinaApproved().amountRefunded(BigDecimal.TEN).build())
                    .isInstanceOf(InvalidCanonicalEventException.class)
                    .hasMessageContaining("only allowed for REFUNDED");
        }

        @Test
        void refundGreaterThanThePaymentIsRejected() {
            assertThatThrownBy(() -> quetzalRefund("1250.01"))
                    .isInstanceOf(InvalidCanonicalEventException.class)
                    .hasMessageContaining("exceeds amount");
        }

        @Test
        void amountRefundedIsOmittedFromJsonWhenAbsent() {
            JsonNode json = CanonicalJson.mapper().valueToTree(andinaApproved().build());
            assertThat(json.has("amountRefunded")).isFalse();
        }

        private PremiumPaymentEvent quetzalRefund(String cumulative) {
            return PremiumPaymentEvent.builder()
                    .provider(Provider.QUETZAL)
                    .providerTransactionId("ch_7Hk2Lm9QpZ")
                    .providerEventId("evt_01JDQ9W4R6Y2")
                    .policyNumber("POL-MX-000412785")
                    .status(PaymentStatus.REFUNDED)
                    .amount(new BigDecimal("1250.00"))
                    .amountRefunded(new BigDecimal(cumulative))
                    .statusChangedAt(Instant.parse("2026-11-29T17:59:55Z"))
                    .receivedAt(Instant.parse("2026-11-29T18:00:03Z"))
                    .correlationId("2b9e4f61-8c3a-4d7e-b1f0-6a5c2d9e8f13")
                    .build();
        }
    }

    @Nested
    class Amounts {

        @Test
        void copAndMxnAlwaysHaveTwoDecimals() {
            PremiumPaymentEvent event = andinaApproved().amount(new BigDecimal("185000")).build();
            assertThat(event.amount()).isEqualTo("185000.00");
        }

        @Test
        void clpHasNoDecimals() {
            PremiumPaymentEvent event = PremiumPaymentEvent.builder()
                    .provider(Provider.AUSTRAL)
                    .providerTransactionId("AUS-P-77120")
                    .providerEventId("aus_ev_93f0c1d2")
                    .policyNumber("POL-CL-000093311")
                    .status(PaymentStatus.CAPTURED)
                    .amount(new BigDecimal("45990"))
                    .statusChangedAt(Instant.parse("2026-11-28T17:05:40Z"))
                    .receivedAt(Instant.parse("2026-11-28T17:05:44Z"))
                    .correlationId("0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9")
                    .build();

            assertThat(event.amount()).isEqualTo("45990");
            assertThat(event.currency()).isEqualTo("CLP");
            assertThat(SCHEMA.validate(event)).isEmpty();
        }

        @Test
        void amountThatWouldNeedRoundingIsRejectedNotRounded() {
            assertThatThrownBy(() -> andinaApproved().amount(new BigDecimal("185000.005")).build())
                    .isInstanceOf(InvalidCanonicalEventException.class)
                    .hasMessageContaining("more decimals than COP");
        }

        @Test
        void negativeAmountIsRejected() {
            assertThatThrownBy(() -> andinaApproved().amount(new BigDecimal("-1.00")).build())
                    .isInstanceOf(InvalidCanonicalEventException.class);
        }
    }

    @Nested
    class Validation {

        @Test
        void policyNumberMustBeNormalized() {
            assertThatThrownBy(() -> andinaApproved().policyNumber("000215560").build())
                    .isInstanceOf(InvalidCanonicalEventException.class)
                    .hasMessageContaining("policyNumber");
        }

        @Test
        void policyNumberMustBelongToTheProvidersCountry() {
            assertThatThrownBy(() -> andinaApproved().policyNumber("POL-MX-000412785").build())
                    .isInstanceOf(InvalidCanonicalEventException.class)
                    .hasMessageContaining("CO policy number");
        }

        @Test
        void transactionIdThatWouldBreakTheEventIdIsRejected() {
            assertThatThrownBy(() -> andinaApproved().providerTransactionId("TRX:CO:1").build())
                    .isInstanceOf(InvalidCanonicalEventException.class)
                    .hasMessageContaining("providerTransactionId");
        }

        @Test
        void errorMessagesNeverContainTheRejectedValue() {
            assertThatThrownBy(() -> andinaApproved().policyNumber("laura.gomez@example.com").build())
                    .hasMessageNotContaining("laura.gomez@example.com");
        }

        @Test
        void schemaRejectsAnEventThatBypassesTheBuilder() {
            PremiumPaymentEvent valid = andinaApproved().build();
            PremiumPaymentEvent tampered = new PremiumPaymentEvent(
                    valid.schemaVersion(), valid.eventId(), valid.paymentKey(), valid.country(),
                    Provider.QUETZAL, // a Colombian payment cannot come from Quetzal
                    valid.providerTransactionId(), valid.providerEventId(), valid.policyNumber(),
                    valid.status(), valid.amount(), null, "MXN",
                    valid.statusChangedAt(), valid.receivedAt(), valid.correlationId());

            assertThat(SCHEMA.validate(tampered)).isNotEmpty();
            assertThatThrownBy(() -> SCHEMA.requireValid(tampered))
                    .isInstanceOf(InvalidCanonicalEventException.class);
        }
    }

    @Test
    void lifecycleRanksDriveTheOrderingRule() {
        assertThat(PaymentStatus.PENDING.rank()).isLessThan(PaymentStatus.CAPTURED.rank());
        assertThat(PaymentStatus.CAPTURED.rank()).isEqualTo(PaymentStatus.FAILED.rank());
        assertThat(PaymentStatus.REFUNDED.rank()).isGreaterThan(PaymentStatus.CAPTURED.rank());
    }
}
