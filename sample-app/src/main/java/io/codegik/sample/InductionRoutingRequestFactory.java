package io.codegik.sample;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.io.IOException;
import java.net.URI;

/**
 * Routes each outbound request either directly to the real service, or — when an
 * induction profile is active for the current request — through the sidecar as
 * an HTTP proxy. Proxying keeps the original absolute URL on the wire, so the
 * sidecar matches on the real {@code scheme://host:port/path} without the app
 * rewriting any URL. In production (no profile) the sidecar is never involved.
 *
 * <p>This is generic: any {@code RestClient} built with it gets the behavior, so
 * adding more external service clients needs no extra routing code.
 */
public class InductionRoutingRequestFactory implements ClientHttpRequestFactory {

    private final ClientHttpRequestFactory direct;
    private final ClientHttpRequestFactory viaSidecar;

    public InductionRoutingRequestFactory(ClientHttpRequestFactory direct, ClientHttpRequestFactory viaSidecar) {
        this.direct = direct;
        this.viaSidecar = viaSidecar;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        boolean induce = InductionContext.profile() != null;
        return (induce ? viaSidecar : direct).createRequest(uri, httpMethod);
    }
}
