package io.codegik.sample;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the outbound payment client.
 *
 * <p>{@code baseUrl} points at the test-induction sidecar's mock-engine port
 * during testing. To run with zero impact against the real service, point it at
 * the real payment URL and don't send induction headers.
 */
@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue("payment-service") String caller,
        @DefaultValue("2000") int connectTimeoutMs,
        @DefaultValue("4000") int readTimeoutMs) {
}
