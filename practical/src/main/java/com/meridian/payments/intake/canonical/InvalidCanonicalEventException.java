package com.meridian.payments.intake.canonical;

/**
 * A notification could not be turned into a valid canonical event.
 *
 * <p>Messages name the field and the rule, never the value: values may come from a payload
 * that contains payer personal data (NFR6).
 */
public class InvalidCanonicalEventException extends RuntimeException {

    public InvalidCanonicalEventException(String message) {
        super(message);
    }
}
