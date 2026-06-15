package io.codegik.sample;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Calls the external payment API. The base URL and timeouts come from
 * {@link PaymentProperties}; when pointed at the sidecar, the induction headers
 * cause the configured fault/behavior to be returned.
 */
@Component
public class PaymentClient {

    private final RestClient restClient;
    private final String caller;

    public PaymentClient(PaymentProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));

        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
        this.caller = props.caller();
    }

    /**
     * Charge a payment. {@code profile}, when non-blank, is forwarded as the
     * induction profile header so the sidecar serves the matching behavior.
     */
    public PaymentResponse charge(PayRequest request, String profile) {
        return restClient.post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    headers.add(InductionHeaders.CALLER, caller);
                    if (profile != null && !profile.isBlank()) {
                        headers.add(InductionHeaders.PROFILE, profile);
                    }
                })
                .body(request)
                .retrieve()
                .body(PaymentResponse.class);
    }
}
