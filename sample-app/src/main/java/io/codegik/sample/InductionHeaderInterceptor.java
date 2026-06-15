package io.codegik.sample;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * When an induction profile is active, stamps the induction headers
 * ({@code x-induction-test-profile} and {@code x-induction-test-caller}) onto the
 * outbound request so the sidecar can select the right behavior. When no profile
 * is active the request is left untouched.
 */
public class InductionHeaderInterceptor implements ClientHttpRequestInterceptor {

    private final String caller;

    public InductionHeaderInterceptor(String caller) {
        this.caller = caller;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String profile = InductionContext.profile();
        if (profile != null && !profile.isBlank()) {
            request.getHeaders().add(InductionHeaders.PROFILE, profile);
            request.getHeaders().add(InductionHeaders.CALLER, caller);
        }
        return execution.execute(request, body);
    }
}
