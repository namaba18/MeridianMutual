package com.meridian.payments.intake.stub;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.meridian.payments.intake.canonical.PremiumPaymentEvent;
import com.meridian.payments.intake.port.NotificationStore;
import com.meridian.payments.intake.port.PortUnavailableException;

/**
 * <b>Stub</b> of the Cosmos DB {@code notifications} container. Keeps the same document shape as
 * SOLUTION_DESIGN §7.2 (status plus the list of provider attempts), keyed by eventId.
 */
@Component
public class InMemoryNotificationStore implements NotificationStore {

    public record Attempt(String providerEventId, Instant receivedAt, String result) {
    }

    public record Document(String eventId, String paymentKey, String processingStatus, List<Attempt> attempts) {
    }

    private final Map<String, Document> documents = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    @Override
    public boolean exists(String eventId, String paymentKey) {
        requireAvailable();
        return documents.containsKey(eventId);
    }

    @Override
    public void saveReceived(PremiumPaymentEvent event) {
        requireAvailable();
        List<Attempt> attempts = new CopyOnWriteArrayList<>();
        attempts.add(new Attempt(event.providerEventId(), event.receivedAt(), "ACCEPTED"));
        documents.putIfAbsent(event.eventId(), new Document(event.eventId(), event.paymentKey(), "RECEIVED", attempts));
    }

    @Override
    public void appendDuplicateAttempt(String eventId, String paymentKey, String providerEventId, Instant receivedAt) {
        requireAvailable();
        Document document = documents.get(eventId);
        if (document != null) {
            document.attempts().add(new Attempt(providerEventId, receivedAt, "DUPLICATE"));
        }
    }

    public Optional<Document> find(String eventId) {
        return Optional.ofNullable(documents.get(eventId));
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void clear() {
        documents.clear();
        available = true;
    }

    private void requireAvailable() {
        if (!available) {
            throw new PortUnavailableException("Cosmos DB unavailable (stub)");
        }
    }
}
