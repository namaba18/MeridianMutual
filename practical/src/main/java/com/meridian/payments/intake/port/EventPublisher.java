package com.meridian.payments.intake.port;

/**
 * Publishes canonical events to the {@code premium-payment-events} topic (Azure Service Bus in
 * production, through a managed identity with the Data Sender role).
 *
 * <p>Contract: when {@link #publish} returns normally, the broker has <b>durably stored</b> the
 * message. Only then may payment-intake answer {@code 2xx} to the provider (ADR-0003).
 */
public interface EventPublisher {

    /** @throws PortUnavailableException if the message could not be durably stored */
    void publish(EventMessage message);
}
