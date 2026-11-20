package com.meridian.payments.intake.port;

/** An external dependency (broker, store) could not complete the operation. Retryable. */
public class PortUnavailableException extends RuntimeException {

    public PortUnavailableException(String message) {
        super(message);
    }

    public PortUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
