package io.codegik.sample;

import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;

/**
 * Calls the external payment API at its <em>real</em> base URL ({@link
 * PaymentProperties#baseUrl()}). Routing is transparent: when the inbound request
 * carries an induction profile, the call is proxied through the sidecar (which
 * mocks it); otherwise it goes straight to the real service. The business method
 * never knows which happened.
 */
@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(PaymentProperties payment, InductionProperties induction) {
        ClientHttpRequestFactory direct = simpleFactory(payment, null);
        ClientHttpRequestFactory viaSidecar = simpleFactory(payment, new Proxy(
                Proxy.Type.HTTP,
                new InetSocketAddress(induction.sidecarHost(), induction.sidecarPort())));

        this.restClient = RestClient.builder()
                .baseUrl(payment.baseUrl())
                .requestFactory(new InductionRoutingRequestFactory(direct, viaSidecar))
                .requestInterceptor(new InductionHeaderInterceptor(induction.caller()))
                .build();
    }

    private static ClientHttpRequestFactory simpleFactory(PaymentProperties payment, Proxy proxy) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(payment.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(payment.readTimeoutMs()));
        if (proxy != null) {
            factory.setProxy(proxy);
        }
        return factory;
    }

    public PaymentResponse charge(PayRequest request) {
        return restClient.post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(PaymentResponse.class);
    }
}
