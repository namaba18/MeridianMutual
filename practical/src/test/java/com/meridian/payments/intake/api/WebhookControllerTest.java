package com.meridian.payments.intake.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.meridian.payments.intake.stub.InMemoryEventPublisher;
import com.meridian.payments.intake.stub.InMemoryNotificationStore;
import com.meridian.payments.intake.stub.InMemoryQuarantinePublisher;
import com.meridian.payments.intake.support.MutableClock;
import com.meridian.payments.intake.support.Samples;

/**
 * HTTP contract of docs/api/webhooks.openapi.yaml, end to end through the Spring application:
 * raw bytes in, status codes and headers out. Secrets come from test properties only.
 */
@SpringBootTest(properties = {
        "intake.webhook-secrets.QUETZAL[0]=" + Samples.QUETZAL_TEST_SECRET,
        "intake.webhook-secrets.ANDINA[0]=" + Samples.ANDINA_TEST_SECRET
})
@AutoConfigureMockMvc
class WebhookControllerTest {

    private static final Instant ANDINA_NOW = OffsetDateTime.parse("2026-11-28T14:05:00-05:00").toInstant();

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(ANDINA_NOW);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private InMemoryEventPublisher topic;
    @Autowired
    private InMemoryQuarantinePublisher quarantine;
    @Autowired
    private InMemoryNotificationStore store;

    @BeforeEach
    void reset() {
        clock.set(ANDINA_NOW);
        topic.clear();
        quarantine.clear();
        store.clear();
    }

    @Test
    void acceptedNotificationAnswers200WithACorrelationIdAndNoBody() throws Exception {
        MvcResult result = mvc.perform(andina("02-transaccion-aprobada.json",
                        "2026-11-28T14:03:11-05:00", "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk="))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().string(""))
                .andReturn();

        assertThat(topic.published()).singleElement()
                .satisfies(m -> assertThat(m.correlationId()).isEqualTo(result.getResponse().getHeader("X-Correlation-Id")));
    }

    @Test
    void retryIsAnswered200SoTheProviderStopsButIsNotPublishedTwice() throws Exception {
        mvc.perform(andina("02-transaccion-aprobada.json",
                "2026-11-28T14:03:11-05:00", "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk="))
                .andExpect(status().isOk());
        mvc.perform(andina("03-transaccion-aprobada-reintento.json",
                "2026-11-28T14:08:11-05:00", "CNu6m5X6B9bMKTsi/IzZfGquIh/KdxTyesBtL4ax424="))
                .andExpect(status().isOk());

        assertThat(topic.published()).hasSize(1);
    }

    @Test
    void quetzalOfficialVectorIsAcceptedThroughHttp() throws Exception {
        clock.set(Instant.ofEpochSecond(1795888803L));

        mvc.perform(post("/v1/webhooks/quetzal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Quetzal-Signature",
                                "t=1795888803,v1=2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031")
                        .content(Samples.quetzal("01-charge-succeeded.json")))
                .andExpect(status().isOk());

        assertThat(topic.published()).hasSize(1);
    }

    @Test
    void invalidSignatureAnswers401AsProblemJsonWithoutDetails() throws Exception {
        mvc.perform(andina("02-transaccion-aprobada.json",
                        "2026-11-28T14:03:11-05:00", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid signature"))
                .andExpect(jsonPath("$.detail").doesNotExist());

        assertThat(topic.published()).isEmpty();
    }

    @Test
    void missingSignatureHeaderAnswers401() throws Exception {
        mvc.perform(post("/v1/webhooks/andina")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Samples.andina("02-transaccion-aprobada.json")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceBusDownAnswers503WithRetryAfter() throws Exception {
        topic.setAvailable(false);

        mvc.perform(andina("02-transaccion-aprobada.json",
                        "2026-11-28T14:03:11-05:00", "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk="))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void bodyLargerThan256KbAnswers413() throws Exception {
        byte[] huge = new byte[WebhookController.MAX_BODY_BYTES + 1];

        mvc.perform(post("/v1/webhooks/andina").contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void bodyIsPassedAsExactBytesEvenWithAnUnexpectedContentType() throws Exception {
        // The signature would fail if the body were decoded and re-encoded on the way in.
        mvc.perform(post("/v1/webhooks/andina")
                        .contentType(MediaType.TEXT_PLAIN)
                        .header("X-Andina-Timestamp", "2026-11-28T14:03:11-05:00")
                        .header("X-Andina-Signature", "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk=")
                        .content(Samples.andina("02-transaccion-aprobada.json")))
                .andExpect(status().isOk());
    }

    @Test
    void authenticNotificationWithUnknownStateIsAnswered200AndQuarantined() throws Exception {
        String timestamp = "2026-11-28T14:04:00-05:00";
        byte[] body = new String(Samples.andina("02-transaccion-aprobada.json"), StandardCharsets.UTF_8)
                .replace("APROBADA", "EN_DISPUTA").getBytes(StandardCharsets.UTF_8);
        String signature = java.util.Base64.getEncoder().encodeToString(com.meridian.payments.intake.provider.Hmac
                .sha256(Samples.ANDINA_TEST_SECRET.getBytes(StandardCharsets.UTF_8), timestamp, body));

        mvc.perform(post("/v1/webhooks/andina")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Andina-Timestamp", timestamp)
                        .header("X-Andina-Signature", signature)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(topic.published()).isEmpty();
        assertThat(quarantine.messages()).hasSize(1);
    }

    @Test
    void providersNotInThisVersionAreNotExposed() throws Exception {
        mvc.perform(post("/v1/webhooks/austral").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyPostIsAllowed() throws Exception {
        mvc.perform(get("/v1/webhooks/andina")).andExpect(status().isMethodNotAllowed());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder andina(
            String sample, String timestamp, String signature) {
        return post("/v1/webhooks/andina")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Andina-Timestamp", timestamp)
                .header("X-Andina-Signature", signature)
                .content(Samples.andina(sample));
    }
}
