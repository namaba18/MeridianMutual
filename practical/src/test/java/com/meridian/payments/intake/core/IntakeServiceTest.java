package com.meridian.payments.intake.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.meridian.payments.intake.canonical.CanonicalJson;
import com.meridian.payments.intake.canonical.EventSchemaValidator;
import com.meridian.payments.intake.canonical.PremiumPaymentEvent;
import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.port.EventMessage;
import com.meridian.payments.intake.port.NotificationStore;
import com.meridian.payments.intake.port.PortUnavailableException;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.PayloadMapper;
import com.meridian.payments.intake.provider.QuarantineReason;
import com.meridian.payments.intake.provider.WebhookRequest;
import com.meridian.payments.intake.provider.andina.AndinaPayloadMapper;
import com.meridian.payments.intake.provider.andina.AndinaSignatureVerifier;
import com.meridian.payments.intake.provider.quetzal.QuetzalPayloadMapper;
import com.meridian.payments.intake.provider.quetzal.QuetzalSignatureVerifier;
import com.meridian.payments.intake.stub.InMemoryEventPublisher;
import com.meridian.payments.intake.stub.InMemoryNotificationStore;
import com.meridian.payments.intake.stub.InMemoryQuarantinePublisher;
import com.meridian.payments.intake.support.MutableClock;
import com.meridian.payments.intake.support.Samples;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The full intake flow (ADR-0003) with the real verifiers, mappers and schema, and in-memory
 * stubs of Service Bus, the quarantine queue and Cosmos DB.
 */
@ExtendWith(OutputCaptureExtension.class)
class IntakeServiceTest {

    // Between Andina samples 02 (14:03:11) and 03 (14:08:11): both inside the 10-minute window.
    private static final Instant NOW = OffsetDateTime.parse("2026-11-28T14:05:00-05:00").toInstant();

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryEventPublisher topic = new InMemoryEventPublisher();
    private final InMemoryQuarantinePublisher quarantine = new InMemoryQuarantinePublisher();
    private final InMemoryNotificationStore store = new InMemoryNotificationStore();
    private IntakeService intake;

    @BeforeEach
    void setUp() {
        intake = service(store);
    }

    @Nested
    class HappyPath {

        @Test
        void validNotificationIsPublishedOnceWithItsBrokerProperties() {
            IntakeResult result = intake.handle(Provider.ANDINA, andina02());

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(result.outcome().httpStatus()).isEqualTo(200);
            assertThat(topic.published()).hasSize(1);

            EventMessage message = topic.published().get(0);
            assertThat(message.messageId()).isEqualTo("ANDINA-TRX-CO-5521907:CAPTURED");
            assertThat(message.sessionId()).isEqualTo("ANDINA-TRX-CO-5521907");
            assertThat(message.correlationId()).isEqualTo(result.correlationId());
            assertThat(message.properties()).containsEntry("country", "CO").containsEntry("status", "CAPTURED")
                    .containsEntry("schemaVersion", "1.0").containsEntry("provider", "Andina");
            assertThat(store.find(message.messageId())).get()
                    .extracting(InMemoryNotificationStore.Document::processingStatus).isEqualTo("RECEIVED");
        }

        @Test
        void publishedBodyIsTheCanonicalEventWithoutPayerData() throws Exception {
            intake.handle(Provider.ANDINA, andina02());

            String body = new String(topic.published().get(0).body(), StandardCharsets.UTF_8);
            assertThat(body).doesNotContain("Laura", "1020304050", "laura.gomez@example.com", "pagador");
            assertThat(CanonicalJson.mapper().readTree(body).get("receivedAt").asText()).isEqualTo(NOW.toString());
        }

        @Test
        void quetzalNotificationIsAcceptedThroughTheSameFlow() {
            clock.set(Instant.ofEpochSecond(1795888803L));
            WebhookRequest request = new WebhookRequest(Samples.quetzal("01-charge-succeeded.json"), Map.of(
                    "Quetzal-Signature",
                    "t=1795888803,v1=2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031"));

            IntakeResult result = intake.handle(Provider.QUETZAL, request);

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(topic.published()).singleElement()
                    .extracting(EventMessage::messageId).isEqualTo("QUETZAL-ch_7Hk2Lm9QpZ:CAPTURED");
        }
    }

    @Nested
    class Duplicates {

        @Test
        void andinaRetryWithANewNotificacionIdIsAcknowledgedButNotPublishedAgain() {
            intake.handle(Provider.ANDINA, andina02());
            IntakeResult retry = intake.handle(Provider.ANDINA, andina03Retry());

            assertThat(retry.outcome()).isEqualTo(IntakeOutcome.DUPLICATE);
            assertThat(retry.outcome().httpStatus()).isEqualTo(200); // so Andina stops retrying
            assertThat(topic.published()).hasSize(1);
        }

        @Test
        void retryIsRecordedSoOperationsCanFindItByItsOwnId() {
            intake.handle(Provider.ANDINA, andina02());
            intake.handle(Provider.ANDINA, andina03Retry());

            assertThat(store.find("ANDINA-TRX-CO-5521907:CAPTURED").orElseThrow().attempts())
                    .extracting(InMemoryNotificationStore.Attempt::providerEventId, InMemoryNotificationStore.Attempt::result)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("ntf-8f1c2a77", "ACCEPTED"),
                            org.assertj.core.groups.Tuple.tuple("ntf-c02d9e13", "DUPLICATE"));
        }

        @Test
        void quetzalRedeliveryWithTheSameIdAndBodyIsADuplicate() {
            clock.set(Instant.ofEpochSecond(1795888803L));

            intake.handle(Provider.QUETZAL, quetzal01());
            IntakeResult again = intake.handle(Provider.QUETZAL, quetzal01());

            assertThat(again.outcome()).isEqualTo(IntakeOutcome.DUPLICATE);
            assertThat(topic.published()).hasSize(1);
        }

        @Test
        void concurrentIdenticalDeliveriesArePublishedOnce() throws Exception {
            // Both can pass the store lookup before either saves; the broker's duplicate
            // detection (layer 2) is what keeps it to one message.
            int deliveries = 8;
            ExecutorService pool = Executors.newFixedThreadPool(deliveries);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<IntakeResult>> results = new ArrayList<>();
            for (int i = 0; i < deliveries; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return intake.handle(Provider.ANDINA, andina02());
                }));
            }
            start.countDown();
            try {
                for (Future<IntakeResult> result : results) {
                    assertThat(result.get(10, TimeUnit.SECONDS).outcome().accepted()).isTrue();
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(topic.published()).hasSize(1);
        }

        @Test
        void storeDownStillAcceptsAndTheBrokerDropsTheDuplicate() {
            store.setAvailable(false);

            IntakeResult first = intake.handle(Provider.ANDINA, andina02());
            IntakeResult retry = intake.handle(Provider.ANDINA, andina03Retry());

            assertThat(first.outcome()).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(retry.outcome()).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(topic.published()).as("layer 2: Service Bus duplicate detection").hasSize(1);
        }
    }

    @Nested
    class Durability {

        @Test
        void serviceBusDownAnswers503AndStoresNothing() {
            topic.setAvailable(false);

            IntakeResult result = intake.handle(Provider.ANDINA, andina02());

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.UNAVAILABLE);
            assertThat(result.outcome().httpStatus()).isEqualTo(503);
            assertThat(store.find("ANDINA-TRX-CO-5521907:CAPTURED"))
                    .as("not marked as received, so the provider's retry is not taken for a duplicate").isEmpty();
        }

        @Test
        void providerRetryAfterServiceBusRecoversIsAccepted() {
            topic.setAvailable(false);
            intake.handle(Provider.ANDINA, andina02());
            topic.setAvailable(true);

            IntakeResult retry = intake.handle(Provider.ANDINA, andina03Retry());

            assertThat(retry.outcome()).isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(topic.published()).hasSize(1);
        }

        @Test
        void failingToSaveTheStatusAfterPublishingStillAnswers200() {
            NotificationStore saveFails = new NotificationStore() {
                @Override
                public boolean exists(String eventId, String paymentKey) {
                    return false;
                }

                @Override
                public void saveReceived(com.meridian.payments.intake.canonical.PremiumPaymentEvent event) {
                    throw new PortUnavailableException("write failed");
                }

                @Override
                public void appendDuplicateAttempt(String eventId, String paymentKey, String id, Instant at) {
                }
            };

            IntakeResult result = service(saveFails).handle(Provider.ANDINA, andina02());

            assertThat(result.outcome()).as("the event is already durable in the topic").isEqualTo(IntakeOutcome.ACCEPTED);
            assertThat(topic.published()).hasSize(1);
        }

        @Test
        void exceedingTheInternalDeadlineAnswers503BeforeTheProviderTimesOut() {
            NotificationStore slowStore = new InMemoryNotificationStore() {
                @Override
                public boolean exists(String eventId, String paymentKey) {
                    clock.advance(Duration.ofSeconds(4)); // slower than the 3 s budget
                    return false;
                }
            };

            IntakeResult result = service(slowStore).handle(Provider.ANDINA, andina02());

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.UNAVAILABLE);
            assertThat(result.reason()).isEqualTo("DEADLINE_EXCEEDED");
            assertThat(topic.published()).isEmpty();
        }
    }

    @Nested
    class Rejections {

        @Test
        void invalidSignatureAnswers401AndStoresNothing() {
            WebhookRequest forged = new WebhookRequest(Samples.andina("02-transaccion-aprobada.json"),
                    Map.of("X-Andina-Timestamp", "2026-11-28T14:03:11-05:00",
                            "X-Andina-Signature", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));

            IntakeResult result = intake.handle(Provider.ANDINA, forged);

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.INVALID_SIGNATURE);
            assertThat(result.outcome().httpStatus()).isEqualTo(401);
            assertThat(topic.published()).isEmpty();
            assertThat(quarantine.messages()).isEmpty();
        }

        @Test
        void missingSecretIsADeploymentErrorAnswered503NotAProviderError() {
            IntakeService noSecrets = new IntakeService(
                    List.of(new AndinaSignatureVerifier(provider -> List.of(), clock)),
                    List.of(new AndinaPayloadMapper()), new EventSchemaValidator(), store, topic, quarantine,
                    clock, Duration.ofSeconds(3), new SimpleMeterRegistry());

            assertThat(noSecrets.handle(Provider.ANDINA, andina02()).outcome()).isEqualTo(IntakeOutcome.UNAVAILABLE);
        }
    }

    @Nested
    class Quarantine {

        @Test
        void authenticButUnknownStateIsAcceptedIntoQuarantineAndNotPublished() {
            IntakeResult result = intake.handle(Provider.ANDINA, signedAndina(
                    sample02().replace("APROBADA", "EN_DISPUTA")));

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.QUARANTINED);
            assertThat(result.outcome().httpStatus()).isEqualTo(200);
            assertThat(topic.published()).isEmpty();
            assertThat(quarantine.messages()).singleElement().satisfies(m -> {
                assertThat(m.reason()).isEqualTo(QuarantineReason.UNKNOWN_PROVIDER_STATUS);
                assertThat(m.providerTransactionId()).isEqualTo("TRX-CO-5521907");
                assertThat(m.correlationId()).isEqualTo(result.correlationId());
                assertThat(m.sanitizedPayload().toString()).doesNotContain("laura.gomez@example.com");
            });
        }

        @Test
        void policyReferenceThatCannotBeNormalizedIsQuarantined() {
            IntakeResult result = intake.handle(Provider.ANDINA, signedAndina(
                    sample02().replace("POL-CO-000187342", "mi poliza")));

            assertThat(result.reason()).isEqualTo("INVALID_POLICY_REFERENCE");
            assertThat(topic.published()).isEmpty();
        }

        @Test
        void eventThatFailsTheSchemaIsQuarantinedAndNeverPublished() {
            // A mapper defect that bypasses the builder: a Colombian payment in MXN.
            PayloadMapper defective = new PayloadMapper() {
                @Override
                public Provider provider() {
                    return Provider.ANDINA;
                }

                @Override
                public MappingResult map(byte[] rawBody, Instant receivedAt, String correlationId) {
                    PremiumPaymentEvent ok = ((MappingResult.Mapped) new AndinaPayloadMapper()
                            .map(rawBody, receivedAt, correlationId)).event();
                    return new MappingResult.Mapped(new PremiumPaymentEvent(ok.schemaVersion(), ok.eventId(),
                            ok.paymentKey(), ok.country(), ok.provider(), ok.providerTransactionId(),
                            ok.providerEventId(), ok.policyNumber(), ok.status(), ok.amount(), null, "MXN",
                            ok.statusChangedAt(), ok.receivedAt(), ok.correlationId()));
                }
            };
            IntakeService guarded = new IntakeService(
                    List.of(new AndinaSignatureVerifier(Samples.secrets(Provider.ANDINA, Samples.ANDINA_TEST_SECRET), clock)),
                    List.of(defective), new EventSchemaValidator(), store, topic, quarantine,
                    clock, Duration.ofSeconds(3), new SimpleMeterRegistry());

            IntakeResult result = guarded.handle(Provider.ANDINA, andina02());

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.QUARANTINED);
            assertThat(result.reason()).isEqualTo("INVALID_PAYLOAD");
            assertThat(topic.published()).as("the schema is the last guard before the topic").isEmpty();
        }

        @Test
        void quarantineQueueDownAnswers503() {
            quarantine.setAvailable(false);

            IntakeResult result = intake.handle(Provider.ANDINA, signedAndina(
                    sample02().replace("APROBADA", "EN_DISPUTA")));

            assertThat(result.outcome()).isEqualTo(IntakeOutcome.UNAVAILABLE);
        }
    }

    @Test
    void logsNeverContainPayerPersonalData(CapturedOutput output) {
        intake.handle(Provider.ANDINA, andina02());
        intake.handle(Provider.ANDINA, andina03Retry());
        intake.handle(Provider.ANDINA, signedAndina(sample02().replace("APROBADA", "EN_DISPUTA")));

        assertThat(output.getAll())
                .contains("outcome=ACCEPTED", "outcome=DUPLICATE", "outcome=QUARANTINED")
                .doesNotContain("Laura", "Gómez", "1020304050", "laura.gomez@example.com", "POL-CO-000187342");
    }

    @Test
    void outcomesAreCountedPerProviderForMonitoring() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        IntakeService measured = new IntakeService(
                List.of(new AndinaSignatureVerifier(Samples.secrets(Provider.ANDINA, Samples.ANDINA_TEST_SECRET), clock)),
                List.of(new AndinaPayloadMapper()), new EventSchemaValidator(), store, topic, quarantine,
                clock, Duration.ofSeconds(3), meters);

        measured.handle(Provider.ANDINA, andina02());
        measured.handle(Provider.ANDINA, andina03Retry());

        assertThat(meters.counter("intake.notifications", "provider", "Andina", "outcome", "DUPLICATE", "reason", "DUPLICATE")
                .count()).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- helpers

    private IntakeService service(NotificationStore notificationStore) {
        return new IntakeService(
                List.of(new QuetzalSignatureVerifier(Samples.secrets(Provider.QUETZAL, Samples.QUETZAL_TEST_SECRET), clock),
                        new AndinaSignatureVerifier(Samples.secrets(Provider.ANDINA, Samples.ANDINA_TEST_SECRET), clock)),
                List.of(new QuetzalPayloadMapper(), new AndinaPayloadMapper()),
                new EventSchemaValidator(), notificationStore, topic, quarantine,
                clock, Duration.ofSeconds(3), new SimpleMeterRegistry());
    }

    private static WebhookRequest quetzal01() {
        return new WebhookRequest(Samples.quetzal("01-charge-succeeded.json"), Map.of(
                "Quetzal-Signature",
                "t=1795888803,v1=2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031"));
    }

    private static WebhookRequest andina02() {
        return new WebhookRequest(Samples.andina("02-transaccion-aprobada.json"), Map.of(
                "X-Andina-Timestamp", "2026-11-28T14:03:11-05:00",
                "X-Andina-Signature", "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk="));
    }

    private static WebhookRequest andina03Retry() {
        return new WebhookRequest(Samples.andina("03-transaccion-aprobada-reintento.json"), Map.of(
                "X-Andina-Timestamp", "2026-11-28T14:08:11-05:00",
                "X-Andina-Signature", "CNu6m5X6B9bMKTsi/IzZfGquIh/KdxTyesBtL4ax424="));
    }

    private static String sample02() {
        return new String(Samples.andina("02-transaccion-aprobada.json"), StandardCharsets.UTF_8);
    }

    /** A modified body, signed with the Andina test secret so it is authentic. */
    private static WebhookRequest signedAndina(String body) {
        String timestamp = "2026-11-28T14:04:00-05:00";
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        String signature = java.util.Base64.getEncoder().encodeToString(
                com.meridian.payments.intake.provider.Hmac.sha256(
                        Samples.ANDINA_TEST_SECRET.getBytes(StandardCharsets.UTF_8), timestamp, raw));
        return new WebhookRequest(raw, Map.of("X-Andina-Timestamp", timestamp, "X-Andina-Signature", signature));
    }
}
