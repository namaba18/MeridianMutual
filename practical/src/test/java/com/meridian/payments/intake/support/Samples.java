package com.meridian.payments.intake.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.port.SecretProvider;

/**
 * Provider samples and test vectors from reference/providers, read as exact raw bytes.
 * The test secrets are the fictional sandbox secrets published in each WEBHOOK_SPEC.md.
 */
public final class Samples {

    public static final String QUETZAL_TEST_SECRET = "qz_test_whsec_4f8a1c2e9b7d";
    public static final String ANDINA_TEST_SECRET = "andina_test_secret_7c3e19ab";

    private Samples() {
    }

    public static byte[] quetzal(String file) {
        return raw("/providers/quetzal-mx/samples/" + file);
    }

    public static byte[] andina(String file) {
        return raw("/providers/andina-co/samples/" + file);
    }

    public static byte[] raw(String classpathResource) {
        try (InputStream in = Samples.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("Sample not found: " + classpathResource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Secret provider holding the given secrets, in order, for one provider. */
    public static SecretProvider secrets(Provider provider, String... values) {
        Map<Provider, List<byte[]>> map = Map.of(provider,
                List.of(values).stream().map(v -> v.getBytes(StandardCharsets.UTF_8)).toList());
        return p -> map.getOrDefault(p, List.of());
    }
}
