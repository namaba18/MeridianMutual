package com.meridian.payments.intake;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest
class IntakeApplicationTest {

    @Test
    void contextLoads() {
        // The Spring context starts with every adapter, port and stub wired.
    }

    @Test
    void publishedEventSchemaIsOnTheClasspath() {
        assertThat(new ClassPathResource("schemas/premium-payment.schema.json").exists()).isTrue();
    }

    @Test
    void providerSamplesAreAvailableToTests() {
        assertThat(new ClassPathResource("providers/quetzal-mx/samples/01-charge-succeeded.json").exists()).isTrue();
        assertThat(new ClassPathResource("providers/andina-co/samples/03-transaccion-aprobada-reintento.json").exists()).isTrue();
    }
}
