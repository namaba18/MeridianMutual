package com.meridian.payments.intake.provider;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.canonical.InvalidCanonicalEventException;

/**
 * Small helpers to read provider payloads strictly and to build sanitized copies.
 * Error messages name the field, never its value (NFR6).
 */
public final class Payloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Payloads() {
    }

    /** @return the parsed JSON object, or null if the body is not a JSON object */
    public static JsonNode parseObject(byte[] rawBody) {
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static String requiredText(JsonNode root, String pointer) {
        JsonNode node = root.at(pointer);
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new InvalidCanonicalEventException(pointer + " is missing or not a string");
        }
        return node.asText();
    }

    public static long requiredLong(JsonNode root, String pointer) {
        JsonNode node = root.at(pointer);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new InvalidCanonicalEventException(pointer + " is missing or not an integer");
        }
        return node.asLong();
    }

    /** @return the integer at the pointer, or null if absent or JSON null */
    public static Long optionalLong(JsonNode root, String pointer) {
        JsonNode node = root.at(pointer);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new InvalidCanonicalEventException(pointer + " is not an integer");
        }
        return node.asLong();
    }

    /** @return the text at the pointer, or null if it cannot be read (used for quarantine metadata) */
    public static String textOrNull(JsonNode root, String pointer) {
        if (root == null) {
            return null;
        }
        JsonNode node = root.at(pointer);
        return node.isTextual() ? node.asText() : null;
    }

    /**
     * Copies only the allowlisted JSON pointers. Anything else, including payer personal data and
     * fields a provider may add in the future, is left out.
     */
    public static ObjectNode allowlistedCopy(JsonNode root, String... pointers) {
        ObjectNode copy = JsonNodeFactory.instance.objectNode();
        if (root == null) {
            return copy;
        }
        for (String pointer : pointers) {
            JsonNode value = root.at(pointer);
            if (value.isMissingNode() || value.isContainerNode()) {
                continue;
            }
            String[] segments = pointer.substring(1).split("/");
            ObjectNode parent = copy;
            for (int i = 0; i < segments.length - 1; i++) {
                parent = parent.has(segments[i]) ? (ObjectNode) parent.get(segments[i]) : parent.putObject(segments[i]);
            }
            parent.set(segments[segments.length - 1], value.deepCopy());
        }
        return copy;
    }
}
