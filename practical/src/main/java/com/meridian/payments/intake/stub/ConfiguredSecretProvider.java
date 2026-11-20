package com.meridian.payments.intake.stub;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.port.SecretProvider;

/**
 * <b>Stub</b> of {@link SecretProvider} for local runs and tests.
 *
 * <p>Secrets come from the environment ({@code INTAKE_WEBHOOKSECRETS_QUETZAL_0=...}), never from a
 * committed configuration file. The production implementation reads Azure Key Vault with the
 * container's managed identity (see practical/README.md).
 */
@ConfigurationProperties(prefix = "intake")
public class ConfiguredSecretProvider implements SecretProvider {

    private final Map<Provider, List<byte[]>> secrets = new EnumMap<>(Provider.class);

    public ConfiguredSecretProvider(Map<Provider, List<String>> webhookSecrets) {
        if (webhookSecrets != null) {
            webhookSecrets.forEach((provider, values) -> secrets.put(provider,
                    values.stream().map(v -> v.getBytes(StandardCharsets.UTF_8)).toList()));
        }
    }

    @Override
    public List<byte[]> webhookSecrets(Provider provider) {
        return secrets.getOrDefault(provider, List.of());
    }
}
