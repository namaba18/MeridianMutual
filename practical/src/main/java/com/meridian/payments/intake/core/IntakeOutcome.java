package com.meridian.payments.intake.core;

/**
 * What payment-intake answers the provider (docs/api/webhooks.openapi.yaml, acceptance rule).
 * Every {@code 2xx} outcome means the notification is durably stored (NFR3, ADR-0003).
 */
public enum IntakeOutcome {
    /** New notification, published to the topic. */
    ACCEPTED(200),
    /** Same business event already accepted: nothing published again (FR4). */
    DUPLICATE(200),
    /** Authentic but not mappable: stored in quarantine for review (SOLUTION_DESIGN §3.4). */
    QUARANTINED(200),
    /** Missing or invalid signature, or timestamp outside the window (FR1). Nothing stored. */
    INVALID_SIGNATURE(401),
    /** Could not store it durably in time: the provider must retry. Nothing was accepted. */
    UNAVAILABLE(503);

    private final int httpStatus;

    IntakeOutcome(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean accepted() {
        return httpStatus >= 200 && httpStatus < 300;
    }
}
