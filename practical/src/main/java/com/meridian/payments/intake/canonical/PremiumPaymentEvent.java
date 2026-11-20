package com.meridian.payments.intake.canonical;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Canonical premium payment event, v1 (docs/api/events/premium-payment.schema.json).
 *
 * <p>Provider-independent. It has no field for payer personal data, so names, national ids,
 * emails and payment tokens cannot reach the topic even by mistake (NFR6).
 *
 * <p>Instances are created only through {@link #builder()}, which derives the identifiers,
 * country and currency, and enforces the rules of the schema that do not depend on JSON.
 */
public record PremiumPaymentEvent(
        String schemaVersion,
        String eventId,
        String paymentKey,
        Country country,
        Provider provider,
        String providerTransactionId,
        String providerEventId,
        String policyNumber,
        PaymentStatus status,
        String amount,
        @JsonInclude(JsonInclude.Include.NON_NULL) String amountRefunded,
        String currency,
        Instant statusChangedAt,
        Instant receivedAt,
        String correlationId) {

    public static final String SCHEMA_VERSION = "1.0";

    private static final Pattern POLICY_NUMBER = Pattern.compile("^POL-(MX|CO|CL)-\\d{9}$");

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Provider provider;
        private String providerTransactionId;
        private String providerEventId;
        private String policyNumber;
        private PaymentStatus status;
        private BigDecimal amount;
        private BigDecimal amountRefunded;
        private Instant statusChangedAt;
        private Instant receivedAt;
        private String correlationId;

        private Builder() {
        }

        public Builder provider(Provider provider) {
            this.provider = provider;
            return this;
        }

        public Builder providerTransactionId(String providerTransactionId) {
            this.providerTransactionId = providerTransactionId;
            return this;
        }

        public Builder providerEventId(String providerEventId) {
            this.providerEventId = providerEventId;
            return this;
        }

        /** Must already be normalized by the provider adapter. */
        public Builder policyNumber(String policyNumber) {
            this.policyNumber = policyNumber;
            return this;
        }

        public Builder status(PaymentStatus status) {
            this.status = status;
            return this;
        }

        /** Major unit of the provider's currency. */
        public Builder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        /** Cumulative amount refunded, major unit. Required for REFUNDED only. */
        public Builder amountRefunded(BigDecimal amountRefunded) {
            this.amountRefunded = amountRefunded;
            return this;
        }

        public Builder statusChangedAt(Instant statusChangedAt) {
            this.statusChangedAt = statusChangedAt;
            return this;
        }

        public Builder receivedAt(Instant receivedAt) {
            this.receivedAt = receivedAt;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public PremiumPaymentEvent build() {
            require(provider, "provider");
            require(status, "status");
            require(statusChangedAt, "statusChangedAt");
            require(receivedAt, "receivedAt");
            require(correlationId, "correlationId");
            if (providerEventId == null || providerEventId.isBlank() || providerEventId.length() > 80) {
                throw new InvalidCanonicalEventException("providerEventId is missing or too long");
            }

            Country country = provider.country();
            if (policyNumber == null || !POLICY_NUMBER.matcher(policyNumber).matches()
                    || !policyNumber.startsWith("POL-" + country.name() + "-")) {
                throw new InvalidCanonicalEventException("policyNumber is not a normalized " + country + " policy number");
            }

            String formattedAmount = Amounts.format(amount, country, "amount");
            String formattedRefunded = null;
            if (status == PaymentStatus.REFUNDED) {
                formattedRefunded = Amounts.format(amountRefunded, country, "amountRefunded");
                if (amountRefunded.compareTo(amount) > 0) {
                    throw new InvalidCanonicalEventException("amountRefunded exceeds amount");
                }
            } else if (amountRefunded != null) {
                throw new InvalidCanonicalEventException("amountRefunded is only allowed for REFUNDED");
            }

            String paymentKey = EventIds.paymentKey(provider, providerTransactionId);
            String eventId = EventIds.eventId(paymentKey, status, formattedRefunded);

            return new PremiumPaymentEvent(
                    SCHEMA_VERSION,
                    eventId,
                    paymentKey,
                    country,
                    provider,
                    providerTransactionId,
                    providerEventId,
                    policyNumber,
                    status,
                    formattedAmount,
                    formattedRefunded,
                    country.currency(),
                    statusChangedAt,
                    receivedAt,
                    correlationId);
        }

        private static void require(Object value, String field) {
            if (Objects.isNull(value)) {
                throw new InvalidCanonicalEventException(field + " is required");
            }
        }
    }
}
