package com.meridian.payments.intake.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.payments.intake.canonical.PremiumPaymentEvent;

/** Result of translating a provider payload: a canonical event, or a reason to quarantine it. */
public sealed interface MappingResult permits MappingResult.Mapped, MappingResult.Unmappable {

    record Mapped(PremiumPaymentEvent event) implements MappingResult {
    }

    /**
     * @param providerEventId       provider id of the notification, if it could be read (for FR7 lookups)
     * @param providerTransactionId provider transaction id, if it could be read
     * @param sanitizedPayload      allowlisted copy of the payload <b>without payer personal data</b>,
     *                              kept for review and replay; null if the body is not JSON
     * @param detail                which rule failed; never contains payload values
     */
    record Unmappable(
            QuarantineReason reason,
            String providerEventId,
            String providerTransactionId,
            JsonNode sanitizedPayload,
            String detail) implements MappingResult {
    }
}
