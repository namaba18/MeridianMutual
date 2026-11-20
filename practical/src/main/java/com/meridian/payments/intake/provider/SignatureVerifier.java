package com.meridian.payments.intake.provider;

import com.meridian.payments.intake.canonical.Provider;

/** Verifies a webhook exactly as its provider specifies (FR1). One implementation per provider. */
public interface SignatureVerifier {

    Provider provider();

    SignatureCheck verify(WebhookRequest request);
}
