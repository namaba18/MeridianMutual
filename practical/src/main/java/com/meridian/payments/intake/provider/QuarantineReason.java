package com.meridian.payments.intake.provider;

/**
 * Why an authentic notification could not become a canonical event (SOLUTION_DESIGN §3.4, §3.5,
 * §5.2). It is accepted with {@code 200} and kept in quarantine, never guessed.
 */
public enum QuarantineReason {
    /** The provider sent a state this adapter does not know. Never defaulted (see PR-214). */
    UNKNOWN_PROVIDER_STATUS,
    /** The body is not JSON, misses a required field, or a field has an unexpected format. */
    INVALID_PAYLOAD,
    /** The policy reference cannot be normalized to {@code POL-<country>-<9 digits>}. */
    INVALID_POLICY_REFERENCE
}
