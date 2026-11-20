package com.meridian.payments.intake.provider;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.meridian.payments.intake.canonical.Country;

/**
 * Normalizes a payment reference to a policy number (SOLUTION_DESIGN §3.5). Needed since Andina
 * API 2.3 sends the reference as typed by the payer, the root cause of INC-0412.
 *
 * <ol>
 *   <li>Trim and upper-case: {@code pol-co-000198004} becomes {@code POL-CO-000198004}.</li>
 *   <li>Exactly 9 digits: prefix the country, {@code 000215560} becomes {@code POL-CO-000215560}
 *       (assumption 9).</li>
 *   <li>Anything else cannot be normalized: the notification is quarantined, never guessed.</li>
 * </ol>
 */
public final class PolicyNumbers {

    private static final Pattern NINE_DIGITS = Pattern.compile("^\\d{9}$");

    private PolicyNumbers() {
    }

    public static Optional<String> normalize(String reference, Country country) {
        if (reference == null) {
            return Optional.empty();
        }
        String candidate = reference.trim().toUpperCase(Locale.ROOT);
        if (candidate.matches("^POL-" + country.name() + "-\\d{9}$")) {
            return Optional.of(candidate);
        }
        if (NINE_DIGITS.matcher(candidate).matches()) {
            return Optional.of("POL-" + country.name() + "-" + candidate);
        }
        return Optional.empty();
    }
}
