package com.meridian.payments.intake.port;

/**
 * Sends notifications that cannot be mapped to the {@code payments-quarantine} queue, where
 * operations reviews and replays them. Same durability contract as {@link EventPublisher}: the
 * notification is answered {@code 200} only after this returns.
 */
public interface QuarantinePublisher {

    /** @throws PortUnavailableException if the message could not be durably stored */
    void quarantine(QuarantineMessage message);
}
