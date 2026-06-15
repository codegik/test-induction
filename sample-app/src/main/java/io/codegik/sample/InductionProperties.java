package io.codegik.sample;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the test-induction sidecar lives, and how this app identifies itself to
 * it. These settings are infrastructure only — they never change the real
 * service URLs the app calls (those stay in {@link PaymentProperties}).
 *
 * <p>Outbound calls are proxied through {@code sidecarHost:sidecarPort} only when
 * the inbound request carries an induction profile; otherwise the app talks to
 * the real service directly.
 */
@ConfigurationProperties(prefix = "induction")
public record InductionProperties(
        @DefaultValue("localhost") String sidecarHost,
        @DefaultValue("8080") int sidecarPort,
        @DefaultValue("payment-service") String caller) {
}
