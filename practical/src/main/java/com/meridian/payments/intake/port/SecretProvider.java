package com.meridian.payments.intake.port;

import java.util.List;

import com.meridian.payments.intake.canonical.Provider;

/**
 * Webhook secrets per provider (NFR6). In production this is backed by Azure Key Vault through
 * a managed identity; secrets are never in code or configuration files.
 *
 * <p>Returns every <b>active</b> secret: normally one, two during a rotation window (current and
 * next), so rotating a secret never rejects valid notifications.
 */
public interface SecretProvider {

    List<byte[]> webhookSecrets(Provider provider);
}
