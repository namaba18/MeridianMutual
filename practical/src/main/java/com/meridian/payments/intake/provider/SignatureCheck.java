package com.meridian.payments.intake.provider;

/**
 * Outcome of verifying a webhook's authenticity (FR1). The failure reason is safe to log and to
 * count in metrics; it never contains header values or body content.
 */
public record SignatureCheck(boolean valid, Failure failure) {

    public enum Failure {
        /** A required signature or timestamp header is absent. */
        MISSING_HEADER,
        /** A header is present but cannot be parsed. */
        MALFORMED_HEADER,
        /** The signed timestamp is outside the provider's tolerance window (replay protection). */
        TIMESTAMP_OUT_OF_WINDOW,
        /** No signature matches any of the active secrets. */
        SIGNATURE_MISMATCH,
        /** No secret is configured for the provider: a deployment error, never a provider error. */
        NO_SECRET_CONFIGURED
    }

    private static final SignatureCheck VALID = new SignatureCheck(true, null);

    public static SignatureCheck ok() {
        return VALID;
    }

    public static SignatureCheck failed(Failure failure) {
        return new SignatureCheck(false, failure);
    }
}
