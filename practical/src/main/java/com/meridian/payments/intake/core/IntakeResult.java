package com.meridian.payments.intake.core;

/**
 * @param reason short machine-readable cause, safe to log and to tag metrics with (never payload data)
 */
public record IntakeResult(IntakeOutcome outcome, String correlationId, String reason) {
}
