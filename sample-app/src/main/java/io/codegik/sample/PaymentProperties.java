package io.codegik.sample;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the outbound payment client.
 *
 * <p>{@code baseUrl} is the <em>real</em> external payment service URL — the same
 * value in every environment, unchanged whether or not induction is used. When a
 * request carries an induction profile the call is transparently proxied through
 * the sidecar (see {@link InductionProperties}); otherwise it hits this URL.
 */
@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(
        @DefaultValue("http://localhost:9099") String baseUrl,
        @DefaultValue("2000") int connectTimeoutMs,
        @DefaultValue("4000") int readTimeoutMs) {
}
