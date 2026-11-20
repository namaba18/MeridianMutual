package com.meridian.payments.intake.api;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.payments.intake.canonical.Provider;
import com.meridian.payments.intake.core.IntakeResult;
import com.meridian.payments.intake.core.IntakeService;
import com.meridian.payments.intake.provider.WebhookRequest;

/**
 * Provider webhook endpoints (docs/api/webhooks.openapi.yaml). Published through the API Manager,
 * which forwards the body byte for byte.
 *
 * <p>The body is received as {@code byte[]} on purpose: it is never parsed or re-serialized
 * before the signature is verified, because the signature covers the exact bytes.
 */
@RestController
@RequestMapping("/v1/webhooks")
public class WebhookController {

    static final int MAX_BODY_BYTES = 256 * 1024;
    static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String RETRY_AFTER_SECONDS = "30";
    private static final String PROBLEM_BASE = "https://api.meridian.example/problems/";

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final IntakeService intake;

    public WebhookController(IntakeService intake) {
        this.intake = intake;
    }

    @PostMapping("/quetzal")
    public ResponseEntity<?> quetzal(@RequestBody(required = false) byte[] body,
                                     @RequestHeader HttpHeaders headers) {
        return receive(Provider.QUETZAL, body, headers);
    }

    @PostMapping("/andina")
    public ResponseEntity<?> andina(@RequestBody(required = false) byte[] body,
                                    @RequestHeader HttpHeaders headers) {
        return receive(Provider.ANDINA, body, headers);
    }

    private ResponseEntity<?> receive(Provider provider, byte[] body, HttpHeaders headers) {
        if (body != null && body.length > MAX_BODY_BYTES) {
            return problem(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "Payload too large", null);
        }

        IntakeResult result = intake.handle(provider, new WebhookRequest(body, firstValues(headers)));

        return switch (result.outcome()) {
            case ACCEPTED, DUPLICATE, QUARANTINED -> ResponseEntity.ok()
                    .header(CORRELATION_HEADER, result.correlationId())
                    .build();
            case INVALID_SIGNATURE -> problem(HttpStatus.UNAUTHORIZED, "invalid-signature", "Invalid signature", null);
            case UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "temporarily-unavailable",
                    "Temporarily unavailable", result.correlationId());
        };
    }

    /**
     * A defect in our code must not look like an acceptance: answer 500 so the provider retries,
     * and never echo internal details or payload content.
     */
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ProblemDetail> unexpected(RuntimeException e) {
        // Type and location only: an exception message may quote payload content (NFR6).
        StackTraceElement at = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
        log.error("Unexpected intake error type={} at={}", e.getClass().getName(), at);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error", null);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String title, String correlationId) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(PROBLEM_BASE + type));
        problem.setTitle(title);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        if (correlationId != null) {
            response.header(CORRELATION_HEADER, correlationId);
        }
        return response.body(problem);
    }

    private static Map<String, String> firstValues(HttpHeaders headers) {
        Map<String, String> values = new HashMap<>();
        headers.forEach((name, list) -> {
            if (!list.isEmpty()) {
                values.put(name, list.get(0));
            }
        });
        return values;
    }
}
