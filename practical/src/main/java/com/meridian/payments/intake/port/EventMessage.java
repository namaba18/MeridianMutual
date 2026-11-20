package com.meridian.payments.intake.port;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.meridian.payments.intake.canonical.CanonicalJson;
import com.meridian.payments.intake.canonical.PremiumPaymentEvent;

/**
 * A canonical event as it is sent to the Service Bus topic, with the broker properties defined in
 * docs/api/README.md: routing and filtering never need to parse the body.
 *
 * @param messageId     the eventId: Service Bus duplicate detection drops a second publish (§4.2 layer 2)
 * @param sessionId     the paymentKey: one payment at a time in salesforce-writer (§4.4)
 * @param correlationId end-to-end trace id (NFR7)
 * @param properties    schemaVersion, country, provider, status: used by subscription filters
 */
public record EventMessage(
        byte[] body,
        String messageId,
        String sessionId,
        String correlationId,
        Map<String, String> properties) {

    public static EventMessage from(PremiumPaymentEvent event) {
        try {
            return new EventMessage(
                    CanonicalJson.mapper().writeValueAsBytes(event),
                    event.eventId(),
                    event.paymentKey(),
                    event.correlationId(),
                    Map.of(
                            "schemaVersion", event.schemaVersion(),
                            "country", event.country().name(),
                            "provider", event.provider().displayName(),
                            "status", event.status().name()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Canonical event could not be serialized", e);
        }
    }
}
