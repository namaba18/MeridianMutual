package com.meridian.payments.intake.port;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.provider.QuarantineReason;

/**
 * An authentic notification that could not become a canonical event (SOLUTION_DESIGN §5.2).
 * Carries only allowlisted payload fields, never payer personal data.
 */
public record QuarantineMessage(
        Provider provider,
        QuarantineReason reason,
        String providerEventId,
        String providerTransactionId,
        JsonNode sanitizedPayload,
        String detail,
        String correlationId,
        Instant receivedAt) {
}
