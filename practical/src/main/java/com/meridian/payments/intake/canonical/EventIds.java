package com.meridian.payments.intake.canonical;

import java.util.regex.Pattern;

/**
 * Builds the business identifiers of the canonical event (SOLUTION_DESIGN §3.1, §4.1).
 *
 * <ul>
 *   <li>{@code paymentKey = <PROVIDER>-<providerTransactionId>}: one per payment, written to
 *       Salesforce {@code Payment_Key__c}.</li>
 *   <li>{@code eventId = <paymentKey>:<status>}, or {@code <paymentKey>:REFUNDED:<amountRefunded>}
 *       for refunds: the idempotency key. It never uses the provider's delivery id, because
 *       Andina changes it on every retry.</li>
 * </ul>
 */
public final class EventIds {

    /** Characters allowed in a provider transaction id; ':' would break the eventId format. */
    private static final Pattern TRANSACTION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,70}$");

    private EventIds() {
    }

    public static String paymentKey(Provider provider, String providerTransactionId) {
        if (providerTransactionId == null || !TRANSACTION_ID.matcher(providerTransactionId).matches()) {
            throw new InvalidCanonicalEventException("providerTransactionId has an unsupported format");
        }
        return provider.keyPrefix() + "-" + providerTransactionId;
    }

    /**
     * @param amountRefunded canonical amount string; required for REFUNDED, ignored otherwise
     */
    public static String eventId(String paymentKey, PaymentStatus status, String amountRefunded) {
        if (status == PaymentStatus.REFUNDED) {
            if (amountRefunded == null) {
                throw new InvalidCanonicalEventException("amountRefunded is required for REFUNDED");
            }
            return paymentKey + ":REFUNDED:" + amountRefunded;
        }
        return paymentKey + ":" + status.name();
    }
}
