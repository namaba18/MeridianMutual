package com.meridian.payments.intake.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The one JSON mapper used to serialize canonical events. Kept independent of Spring's mapper so
 * the wire format cannot change because of an unrelated application setting.
 * Instants are written as ISO-8601 UTC strings ({@code 2026-11-28T19:03:09Z}).
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private CanonicalJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
