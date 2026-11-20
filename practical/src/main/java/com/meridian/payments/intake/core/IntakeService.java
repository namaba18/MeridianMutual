package com.meridian.payments.intake.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.meridian.payments.intake.canonical.EventSchemaValidator;
import com.meridian.payments.intake.canonical.InvalidCanonicalEventException;
import com.meridian.payments.intake.canonical.PremiumPaymentEvent;
import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.port.EventMessage;
import com.meridian.payments.intake.port.EventPublisher;
import com.meridian.payments.intake.port.NotificationStore;
import com.meridian.payments.intake.port.PortUnavailableException;
import com.meridian.payments.intake.port.QuarantineMessage;
import com.meridian.payments.intake.port.QuarantinePublisher;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.PayloadMapper;
import com.meridian.payments.intake.provider.QuarantineReason;
import com.meridian.payments.intake.provider.SignatureCheck;
import com.meridian.payments.intake.provider.SignatureVerifier;
import com.meridian.payments.intake.provider.WebhookRequest;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The intake flow of ADR-0003, the same for every provider (NFR9):
 *
 * <ol>
 *   <li>verify the signature, or answer 401;</li>
 *   <li>map to the canonical event and validate it against the schema, or quarantine it (200);</li>
 *   <li>look up the business key: a duplicate answers 200 without publishing;</li>
 *   <li><b>publish to the topic</b>; if that fails, answer 503 so the provider retries;</li>
 *   <li>save status RECEIVED (best effort: the event is already durable);</li>
 *   <li>answer 200.</li>
 * </ol>
 *
 * A {@code 2xx} is never returned before the notification is durably stored (NFR3).
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final Map<Provider, SignatureVerifier> verifiers = new EnumMap<>(Provider.class);
    private final Map<Provider, PayloadMapper> mappers = new EnumMap<>(Provider.class);
    private final EventSchemaValidator schema;
    private final NotificationStore store;
    private final EventPublisher publisher;
    private final QuarantinePublisher quarantine;
    private final Clock clock;
    private final Duration deadline;
    private final MeterRegistry meters;

    public IntakeService(
            List<SignatureVerifier> verifiers,
            List<PayloadMapper> mappers,
            EventSchemaValidator schema,
            NotificationStore store,
            EventPublisher publisher,
            QuarantinePublisher quarantine,
            Clock clock,
            @Value("${intake.deadline:PT3S}") Duration deadline,
            MeterRegistry meters) {
        verifiers.forEach(v -> this.verifiers.put(v.provider(), v));
        mappers.forEach(m -> this.mappers.put(m.provider(), m));
        this.schema = schema;
        this.store = store;
        this.publisher = publisher;
        this.quarantine = quarantine;
        this.clock = clock;
        this.deadline = deadline;
        this.meters = meters;
    }

    public boolean supports(Provider provider) {
        return verifiers.containsKey(provider) && mappers.containsKey(provider);
    }

    public IntakeResult handle(Provider provider, WebhookRequest request) {
        String correlationId = UUID.randomUUID().toString();
        Instant receivedAt = clock.instant();
        IntakeResult result = process(provider, request, correlationId, receivedAt);

        meters.counter("intake.notifications",
                "provider", provider.displayName(),
                "outcome", result.outcome().name(),
                "reason", result.reason()).increment();
        log.info("intake provider={} correlationId={} outcome={} reason={} status={}",
                provider.displayName(), correlationId, result.outcome(), result.reason(), result.outcome().httpStatus());
        return result;
    }

    private IntakeResult process(Provider provider, WebhookRequest request, String correlationId, Instant receivedAt) {
        if (!supports(provider)) {
            return result(IntakeOutcome.UNAVAILABLE, correlationId, "PROVIDER_NOT_CONFIGURED");
        }

        // 1. Authenticity (FR1).
        SignatureCheck signature = verifiers.get(provider).verify(request);
        if (!signature.valid()) {
            if (signature.failure() == SignatureCheck.Failure.NO_SECRET_CONFIGURED) {
                log.error("No webhook secret configured provider={} correlationId={}", provider.displayName(), correlationId);
                return result(IntakeOutcome.UNAVAILABLE, correlationId, signature.failure().name());
            }
            return result(IntakeOutcome.INVALID_SIGNATURE, correlationId, signature.failure().name());
        }

        // 2. Translate to the canonical event (FR2); never guess what cannot be mapped.
        MappingResult mapping = mappers.get(provider).map(request.rawBody(), receivedAt, correlationId);
        if (mapping instanceof MappingResult.Unmappable unmappable) {
            return toQuarantine(provider, unmappable, correlationId, receivedAt);
        }
        PremiumPaymentEvent event = ((MappingResult.Mapped) mapping).event();
        try {
            schema.requireValid(event); // producer strict: last guard before the topic
        } catch (InvalidCanonicalEventException e) {
            return toQuarantine(provider, new MappingResult.Unmappable(QuarantineReason.INVALID_PAYLOAD,
                    event.providerEventId(), event.providerTransactionId(), null, e.getMessage()), correlationId, receivedAt);
        }

        // 3. Duplicate check (FR4, layer 1). Degrades gracefully if the store is down (ADR-0003).
        try {
            if (store.exists(event.eventId(), event.paymentKey())) {
                appendAttemptQuietly(event, receivedAt);
                return result(IntakeOutcome.DUPLICATE, correlationId, "DUPLICATE");
            }
        } catch (PortUnavailableException e) {
            log.warn("Notification store unavailable, publishing without duplicate check correlationId={} eventId={}",
                    correlationId, event.eventId());
        }

        // 4. Durable publish: the acceptance point (NFR3). Respect the internal deadline (NFR4).
        if (Duration.between(receivedAt, clock.instant()).compareTo(deadline) > 0) {
            return result(IntakeOutcome.UNAVAILABLE, correlationId, "DEADLINE_EXCEEDED");
        }
        try {
            publisher.publish(EventMessage.from(event));
        } catch (PortUnavailableException e) {
            return result(IntakeOutcome.UNAVAILABLE, correlationId, "PUBLISH_FAILED");
        }

        // 5. Status for operations (FR7). Best effort: the event is already durable.
        try {
            store.saveReceived(event);
        } catch (PortUnavailableException e) {
            log.warn("Could not save RECEIVED status; salesforce-writer will create it correlationId={} eventId={}",
                    correlationId, event.eventId());
        }
        return result(IntakeOutcome.ACCEPTED, correlationId, "PUBLISHED");
    }

    private IntakeResult toQuarantine(Provider provider, MappingResult.Unmappable unmappable,
                                      String correlationId, Instant receivedAt) {
        try {
            quarantine.quarantine(new QuarantineMessage(provider, unmappable.reason(), unmappable.providerEventId(),
                    unmappable.providerTransactionId(), unmappable.sanitizedPayload(), unmappable.detail(),
                    correlationId, receivedAt));
        } catch (PortUnavailableException e) {
            return result(IntakeOutcome.UNAVAILABLE, correlationId, "QUARANTINE_FAILED");
        }
        log.warn("Quarantined provider={} correlationId={} reason={} detail={}",
                provider.displayName(), correlationId, unmappable.reason(), unmappable.detail());
        return result(IntakeOutcome.QUARANTINED, correlationId, unmappable.reason().name());
    }

    private void appendAttemptQuietly(PremiumPaymentEvent event, Instant receivedAt) {
        try {
            store.appendDuplicateAttempt(event.eventId(), event.paymentKey(), event.providerEventId(), receivedAt);
        } catch (PortUnavailableException e) {
            log.warn("Could not record duplicate attempt eventId={}", event.eventId());
        }
    }

    private static IntakeResult result(IntakeOutcome outcome, String correlationId, String reason) {
        return new IntakeResult(outcome, correlationId, reason);
    }
}
