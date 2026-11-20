package com.meridian.payments.intake.canonical;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Payment providers. Each one serves exactly one country.
 * Adding a fourth provider means adding a constant here and a new adapter (NFR9).
 */
public enum Provider {
    QUETZAL("Quetzal", Country.MX),
    ANDINA("Andina", Country.CO),
    AUSTRAL("Austral", Country.CL);

    private final String displayName;
    private final Country country;

    Provider(String displayName, Country country) {
        this.displayName = displayName;
        this.country = country;
    }

    /** Value used in the canonical event and in Salesforce {@code Provider__c}. */
    @JsonValue
    public String displayName() {
        return displayName;
    }

    public Country country() {
        return country;
    }

    /** Prefix of {@code paymentKey} and {@code eventId}, for example {@code ANDINA}. */
    public String keyPrefix() {
        return name();
    }
}
