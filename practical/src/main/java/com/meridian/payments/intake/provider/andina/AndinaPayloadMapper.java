package com.meridian.payments.intake.provider.andina;

import static com.meridian.payments.intake.provider.Payloads.requiredText;
import static com.meridian.payments.intake.provider.Payloads.textOrNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

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
 * Andina Pay (CO), API 2.3, to canonical event (SOLUTION_DESIGN §3.3, §3.4, §3.5).
 * Amounts arrive as decimal strings in the major unit; times as ISO-8601 with offset; the
 * reference as typed by the payer (normalized here). {@code notificacion_id} changes on every
 * retry, so it is kept only as {@code providerEventId}, never as an idempotency key.
 */
@Component
public class AndinaPayloadMapper implements PayloadMapper {

    /** Fields kept in quarantine. {@code pagador} (name, national id, email) is never kept. */
    private static final String[] ALLOWLIST = {
            "/notificacion_id", "/fecha_notificacion",
            "/transaccion/id", "/transaccion/estado", "/transaccion/valor", "/transaccion/moneda",
            "/transaccion/referencia", "/transaccion/medio_pago", "/transaccion/fecha_transaccion",
            "/transaccion/motivo_reverso", "/transaccion/canal"
    };

    private static final String DECIMAL = "^\\d{1,14}(\\.\\d{1,2})?$";

    @Override
    public Provider provider() {
        return Provider.ANDINA;
    }

    @Override
    public MappingResult map(byte[] rawBody, Instant receivedAt, String correlationId) {
        JsonNode root = Payloads.parseObject(rawBody);
        if (root == null) {
            return unmappable(QuarantineReason.INVALID_PAYLOAD, null, "body is not a JSON object");
        }
        try {
            String estado = requiredText(root, "/transaccion/estado");
            PaymentStatus status = switch (estado) {
                case "PENDIENTE" -> PaymentStatus.PENDING;
                case "APROBADA" -> PaymentStatus.CAPTURED;
                case "RECHAZADA" -> PaymentStatus.FAILED;
                case "REVERSADA" -> PaymentStatus.REFUNDED;
                default -> null;
            };
            if (status == null) {
                return unmappable(QuarantineReason.UNKNOWN_PROVIDER_STATUS, root, "unknown transaccion.estado");
            }

            if (!"COP".equals(requiredText(root, "/transaccion/moneda"))) {
                throw new InvalidCanonicalEventException("/transaccion/moneda is not COP");
            }

            var policyNumber = PolicyNumbers.normalize(textOrNull(root, "/transaccion/referencia"), Provider.ANDINA.country());
            if (policyNumber.isEmpty()) {
                return unmappable(QuarantineReason.INVALID_POLICY_REFERENCE, root, "referencia is not a CO policy number");
            }

            BigDecimal valor = decimal(requiredText(root, "/transaccion/valor"));
            PremiumPaymentEvent.Builder event = PremiumPaymentEvent.builder()
                    .provider(Provider.ANDINA)
                    .providerEventId(requiredText(root, "/notificacion_id"))
                    .providerTransactionId(requiredText(root, "/transaccion/id"))
                    .policyNumber(policyNumber.get())
                    .status(status)
                    .amount(valor)
                    .statusChangedAt(instant(requiredText(root, "/transaccion/fecha_transaccion")))
                    .receivedAt(receivedAt)
                    .correlationId(correlationId);
            if (status == PaymentStatus.REFUNDED) {
                event.amountRefunded(valor); // Andina reversals are always full (assumption 2)
            }
            return new MappingResult.Mapped(event.build());
        } catch (InvalidCanonicalEventException e) {
            return unmappable(QuarantineReason.INVALID_PAYLOAD, root, e.getMessage());
        }
    }

    private static BigDecimal decimal(String value) {
        if (!value.matches(DECIMAL)) {
            throw new InvalidCanonicalEventException("/transaccion/valor is not a decimal amount");
        }
        return new BigDecimal(value);
    }

    private static Instant instant(String isoWithOffset) {
        try {
            return OffsetDateTime.parse(isoWithOffset).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidCanonicalEventException("/transaccion/fecha_transaccion is not ISO-8601 with offset");
        }
    }

    private static MappingResult.Unmappable unmappable(QuarantineReason reason, JsonNode root, String detail) {
        return new MappingResult.Unmappable(
                reason,
                textOrNull(root, "/notificacion_id"),
                textOrNull(root, "/transaccion/id"),
                root == null ? null : Payloads.allowlistedCopy(root, ALLOWLIST),
                detail);
    }
}
