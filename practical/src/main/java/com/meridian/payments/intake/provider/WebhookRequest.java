package com.meridian.payments.intake.provider;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A webhook exactly as received: the raw body bytes (never re-serialized, because signatures are
 * computed over them) and the request headers, looked up case-insensitively.
 */
public final class WebhookRequest {

    private final byte[] rawBody;
    private final Map<String, String> headers;

    public WebhookRequest(byte[] rawBody, Map<String, String> headers) {
        this.rawBody = rawBody == null ? new byte[0] : rawBody.clone();
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            copy.putAll(headers);
        }
        this.headers = copy;
    }

    public byte[] rawBody() {
        return rawBody.clone();
    }

    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name)).map(String::trim).filter(v -> !v.isEmpty());
    }
}
