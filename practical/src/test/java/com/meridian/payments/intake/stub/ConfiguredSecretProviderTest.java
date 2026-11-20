package com.meridian.payments.intake.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.meridian.payments.intake.canonical.Provider;

class ConfiguredSecretProviderTest {

    @Test
    void secretsAreReadFromEnvironmentVariablesIncludingARotationPair() {
        StandardEnvironment environment = new StandardEnvironment();
        // Spring maps INTAKE_WEBHOOKSECRETS_... to properties only for sources named like the real
        // system environment, so the test source uses that naming.
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", Map.of(
                "INTAKE_WEBHOOKSECRETS_ANDINA_0", "current-secret",
                "INTAKE_WEBHOOKSECRETS_ANDINA_1", "next-secret",
                "INTAKE_WEBHOOKSECRETS_QUETZAL_0", "quetzal-secret")));

        ConfiguredSecretProvider provider = new Binder(ConfigurationPropertySources.get(environment))
                .bindOrCreate("intake", ConfiguredSecretProvider.class);

        assertThat(provider.webhookSecrets(Provider.ANDINA))
                .extracting(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .containsExactly("current-secret", "next-secret");
        assertThat(provider.webhookSecrets(Provider.QUETZAL)).hasSize(1);
        assertThat(provider.webhookSecrets(Provider.AUSTRAL)).isEqualTo(List.of());
    }
}
