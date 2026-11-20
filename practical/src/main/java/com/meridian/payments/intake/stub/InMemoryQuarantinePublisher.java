package com.meridian.payments.intake.stub;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.meridian.payments.intake.port.PortUnavailableException;
import com.meridian.payments.intake.port.QuarantineMessage;
import com.meridian.payments.intake.port.QuarantinePublisher;

/** <b>Stub</b> of the {@code payments-quarantine} Service Bus queue. */
@Component
public class InMemoryQuarantinePublisher implements QuarantinePublisher {

    private final List<QuarantineMessage> messages = new CopyOnWriteArrayList<>();
    private volatile boolean available = true;

    @Override
    public void quarantine(QuarantineMessage message) {
        if (!available) {
            throw new PortUnavailableException("Quarantine queue unavailable (stub)");
        }
        messages.add(message);
    }

    public List<QuarantineMessage> messages() {
        return List.copyOf(messages);
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void clear() {
        messages.clear();
        available = true;
    }
}
