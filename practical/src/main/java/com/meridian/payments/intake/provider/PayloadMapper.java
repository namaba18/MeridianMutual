package com.meridian.payments.intake.provider;

import java.time.Instant;

import com.meridian.payments.intake.canonical.Provider;

/**
 * Translates one provider's payload into the canonical event (FR2). Everything provider-specific
 * (field names, units, states, time formats) lives in its implementation; adding a provider adds
 * an implementation and changes nothing else (NFR9).
 */
public interface PayloadMapper {

    Provider provider();

    /**
     * @param rawBody       the body exactly as received (already authenticated)
     * @param receivedAt    when Meridian received the notification
     * @param correlationId id assigned by payment-intake for end-to-end tracing
     */
    MappingResult map(byte[] rawBody, Instant receivedAt, String correlationId);
}
