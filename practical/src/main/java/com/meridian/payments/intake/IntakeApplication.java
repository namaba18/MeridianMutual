package com.meridian.payments.intake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * payment-intake: receives provider webhooks, verifies them, maps them to the canonical
 * premium payment event and publishes it durably before answering the provider (ADR-0003).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IntakeApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntakeApplication.class, args);
    }
}
