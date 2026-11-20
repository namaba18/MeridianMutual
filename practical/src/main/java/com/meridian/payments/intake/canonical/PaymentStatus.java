package com.meridian.payments.intake.canonical;

/**
 * Canonical payment lifecycle (SOLUTION_DESIGN §3.4). Maps 1:1 to Salesforce {@code Status__c}.
 *
 * <p>The rank drives the ordering rule (SOLUTION_DESIGN §4.3): a payment can only move to a
 * higher rank, so a late event with a lower rank is stale. CAPTURED and FAILED share a rank
 * because neither can follow the other.
 */
public enum PaymentStatus {
    PENDING(1),
    CAPTURED(2),
    FAILED(2),
    REFUNDED(3);

    private final int rank;

    PaymentStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
