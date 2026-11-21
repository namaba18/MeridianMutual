package com.meridian.payments.intake.provider.quetzal;

import static com.meridian.payments.intake.provider.Payloads.optionalLong;
import static com.meridian.payments.intake.provider.Payloads.requiredLong;
import static com.meridian.payments.intake.provider.Payloads.requiredText;
import static com.meridian.payments.intake.provider.Payloads.textOrNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.payments.intake.canonical.InvalidCanonicalEventException;
import com.meridian.payments.intake.canonical.PaymentStatus;
import com.meridian.payments.intake.canonical.PremiumPaymentEvent;
import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.PayloadMapper;
import com.meridian.payments.intake.provider.Payloads;
import com.meridian.payments.intake.provider.PolicyNumbers;
import com.meridian.payments.intake.provider.QuarantineReason;

/**
 * Quetzal Pagos (MX), API version {@code 2025-06-01}, to canonical event (SOLUTION_DESIGN §3.3, §3.4).
 * Amounts arrive as integers in centavos; currency in lower case; times in Unix seconds.
 */
@Component
public class QuetzalPayloadMapper implements PayloadMapper {

    /**
     * The only Quetzal API version this adapter maps. A new version could change what a field
     * means (for example the unit of {@code amount}) without changing its shape, so any other
     * version, or none, is quarantined instead of mapped (docs/api/README.md, assumption 14).
     */
    static final String SUPPORTED_API_VERSION = "2025-06-01";

    /** Fields kept in quarantine. {@code customer} and {@code payment_method} are never kept. */
    private static final String[] ALLOWLIST = {
            "/id", "/type", "/api_version", "/created",
            "/data/charge/id", "/data/charge/amount", "/data/charge/amount_refunded",
            "/data/charge/currency", "/data/charge/status", "/data/charge/failure_code",
            "/data/charge/reference", "/data/charge/paid_at", "/data/charge/refunded_at"
    };

    @Override
    public Provider provider() {
        return Provider.QUETZAL;
    }

    @Override
    public MappingResult map(byte[] rawBody, Instant receivedAt, String correlationId) {
        JsonNode root = Payloads.parseObject(rawBody);
        if (root == null) {
            return unmappable(QuarantineReason.INVALID_PAYLOAD, null, "body is not a JSON object");
        }
        if (!SUPPORTED_API_VERSION.equals(textOrNull(root, "/api_version"))) {
            return unmappable(QuarantineReason.UNSUPPORTED_PROVIDER_VERSION, root,
                    "api_version is not " + SUPPORTED_API_VERSION);
        }
        try {
            String type = requiredText(root, "/type");
            PaymentStatus status = switch (type) {
                case "charge.succeeded" -> PaymentStatus.CAPTURED;
                case "charge.failed" -> PaymentStatus.FAILED;
                case "charge.refunded" -> PaymentStatus.REFUNDED;
                default -> null;
            };
            if (status == null) {
                return unmappable(QuarantineReason.UNKNOWN_PROVIDER_STATUS, root, "unknown event type");
            }

            String currency = requiredText(root, "/data/charge/currency");
            if (!"MXN".equals(currency.toUpperCase(Locale.ROOT))) {
                throw new InvalidCanonicalEventException("/data/charge/currency is not MXN");
            }

            var policyNumber = PolicyNumbers.normalize(textOrNull(root, "/data/charge/reference"), Provider.QUETZAL.country());
            if (policyNumber.isEmpty()) {
                return unmappable(QuarantineReason.INVALID_POLICY_REFERENCE, root, "reference is not a MX policy number");
            }

            long created = requiredLong(root, "/created");
            PremiumPaymentEvent.Builder event = PremiumPaymentEvent.builder()
                    .provider(Provider.QUETZAL)
                    .providerEventId(requiredText(root, "/id"))
                    .providerTransactionId(requiredText(root, "/data/charge/id"))
                    .policyNumber(policyNumber.get())
                    .status(status)
                    .amount(fromCentavos(requiredLong(root, "/data/charge/amount")))
                    .receivedAt(receivedAt)
                    .correlationId(correlationId);

            switch (status) {
                case CAPTURED -> event.statusChangedAt(epoch(optionalLong(root, "/data/charge/paid_at"), created));
                case REFUNDED -> event
                        .amountRefunded(fromCentavos(requiredLong(root, "/data/charge/amount_refunded")))
                        .statusChangedAt(epoch(optionalLong(root, "/data/charge/refunded_at"), created));
                default -> event.statusChangedAt(Instant.ofEpochSecond(created)); // failed: no charge timestamp
            }
            return new MappingResult.Mapped(event.build());
        } catch (InvalidCanonicalEventException e) {
            return unmappable(QuarantineReason.INVALID_PAYLOAD, root, e.getMessage());
        }
    }

    private static BigDecimal fromCentavos(long minorUnits) {
        return BigDecimal.valueOf(minorUnits).movePointLeft(2);
    }

    /** The charge timestamp when present, otherwise the event time. */
    private static Instant epoch(Long chargeTimestamp, long eventCreated) {
        return Instant.ofEpochSecond(chargeTimestamp != null ? chargeTimestamp : eventCreated);
    }

    private static MappingResult.Unmappable unmappable(QuarantineReason reason, JsonNode root, String detail) {
        return new MappingResult.Unmappable(
                reason,
                textOrNull(root, "/id"),
                textOrNull(root, "/data/charge/id"),
                root == null ? null : Payloads.allowlistedCopy(root, ALLOWLIST),
                detail);
    }
}
