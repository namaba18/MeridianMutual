package com.meridian.payments.intake.canonical;

/**
 * Countries served by the integration, with the currency of each one and how many decimals
 * that currency has in the canonical event (CLP has no minor unit).
 */
public enum Country {
    MX("MXN", 2),
    CO("COP", 2),
    CL("CLP", 0);

    private final String currency;
    private final int currencyDecimals;

    Country(String currency, int currencyDecimals) {
        this.currency = currency;
        this.currencyDecimals = currencyDecimals;
    }

    public String currency() {
        return currency;
    }

    public int currencyDecimals() {
        return currencyDecimals;
    }
}
