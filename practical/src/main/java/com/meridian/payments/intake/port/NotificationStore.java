package com.meridian.payments.intake.port;

import java.time.Instant;

import com.meridian.payments.intake.canonical.PremiumPaymentEvent;

/**
 * The {@code notifications} container (Cosmos DB in production, SOLUTION_DESIGN §7): one document
 * per business event, partitioned by paymentKey. Used to stop duplicates before they are published
 * (§4.2 layer 1) and to let operations find a notification (FR7).
 *
 * <p>Not on the durability path: if it is unavailable, payment-intake still publishes and relies
 * on Service Bus duplicate detection and the ordering rule (ADR-0003, graceful degradation).
 */
public interface NotificationStore {

    /** @throws PortUnavailableException if the store cannot be reached */
    boolean exists(String eventId, String paymentKey);

    /** Creates the document with status RECEIVED and its first attempt. */
    void saveReceived(PremiumPaymentEvent event);

    /** Appends a provider retry to {@code attempts}, so operations can search by its id. */
    void appendDuplicateAttempt(String eventId, String paymentKey, String providerEventId, Instant receivedAt);
}
