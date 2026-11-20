package com.meridian.payments.intake.canonical;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Validates every canonical event against the published JSON Schema before it is published
 * (docs/api/README.md: "producer strict"). The schema is the file in docs/api/events, copied to
 * the classpath by the build, so code and contract cannot drift apart.
 */
@Component
public class EventSchemaValidator {

    static final String SCHEMA_RESOURCE = "/schemas/premium-payment.schema.json";

    private final JsonSchema schema;

    public EventSchemaValidator() {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .build();
        try (InputStream in = EventSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Event schema not found on the classpath: " + SCHEMA_RESOURCE);
            }
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the schema violations, empty when the event is valid */
    public List<String> validate(PremiumPaymentEvent event) {
        JsonNode json = CanonicalJson.mapper().valueToTree(event);
        return schema.validate(json).stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();
    }

    /** @throws InvalidCanonicalEventException when the event does not satisfy the schema */
    public void requireValid(PremiumPaymentEvent event) {
        List<String> violations = validate(event);
        if (!violations.isEmpty()) {
            throw new InvalidCanonicalEventException("event does not match schema v1: " + violations);
        }
    }
}
