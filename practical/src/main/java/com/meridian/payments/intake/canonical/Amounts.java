package com.meridian.payments.intake.canonical;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Formats money for the canonical event: major unit, as a string, with exactly the decimals of
 * the currency (two for MXN and COP, none for CLP). Never rounds: an amount that would lose
 * precision is rejected instead of silently changed.
 */
public final class Amounts {

    private static final BigDecimal MAX_EXCLUSIVE = new BigDecimal("100000000000000"); // 14 integer digits

    private Amounts() {
    }

    public static String format(BigDecimal amount, Country country, String field) {
        if (amount == null) {
            throw new InvalidCanonicalEventException(field + " is required");
        }
        if (amount.signum() < 0) {
            throw new InvalidCanonicalEventException(field + " must not be negative");
        }
        if (amount.compareTo(MAX_EXCLUSIVE) >= 0) {
            throw new InvalidCanonicalEventException(field + " is too large");
        }
        try {
            return amount.setScale(country.currencyDecimals(), RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            throw new InvalidCanonicalEventException(
                    field + " has more decimals than " + country.currency() + " allows");
        }
    }
}
