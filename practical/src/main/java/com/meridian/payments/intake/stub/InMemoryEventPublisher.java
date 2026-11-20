package com.meridian.payments.intake.stub;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.meridian.payments.intake.port.EventMessage;
import com.meridian.payments.intake.port.EventPublisher;
import com.meridian.payments.intake.port.PortUnavailableException;

/**
 * <b>Stub</b> of the Service Bus topic. Emulates the two broker behaviours the design relies on:
 * duplicate detection by {@code messageId}, and being unavailable (for failure tests).
 * The production implementation uses the Azure Service Bus SDK with a managed identity.
 */
@Component
public class InMemoryEventPublisher implements EventPublisher {

    private final List<EventMessage> published = new CopyOnWriteArrayList<>();
    private final Set<String> seenMessageIds = ConcurrentHashMap.newKeySet();
    private volatile boolean available = true;

    @Override
    public void publish(EventMessage message) {
        if (!available) {
            throw new PortUnavailableException("Service Bus unavailable (stub)");
        }
        if (seenMessageIds.add(message.messageId())) {
            published.add(message);
        }
        // else: duplicate detection drops it silently, as Service Bus does
    }

    public List<EventMessage> published() {
        return List.copyOf(published);
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void clear() {
        published.clear();
        seenMessageIds.clear();
        available = true;
    }
}
