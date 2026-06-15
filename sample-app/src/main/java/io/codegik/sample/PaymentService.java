package io.codegik.sample;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drives the payment client and translates the various induced failures into a
 * clear outcome, so the demo shows exactly which fault the sidecar produced.
 *
 * <p>Note on classification: a read timeout or connection reset surfaces while
 * the response body is being read, so Spring wraps it in a plain
 * {@link RestClientException} ("Error while extracting response") rather than a
 * {@code ResourceAccessException}. To tell timeout/reset apart from a genuine
 * malformed-body decode error, we inspect the exception's root cause — and we
 * check {@link SocketTimeoutException}/{@link SocketException} before falling
 * back to "malformed", because Jackson's parse error is itself an IOException.
 */
@Service
public class PaymentService {

    private final PaymentClient client;

    public PaymentService(PaymentClient client) {
        this.client = client;
    }

    public ResponseEntity<Map<String, Object>> charge(PayRequest request, String profile) {
        try {
            PaymentResponse payment = client.charge(request, profile);
            return ResponseEntity.ok(body("SUCCESS", Map.of("payment", payment)));

        } catch (RestClientResponseException e) {
            // The payment API responded with a 4xx/5xx status.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(body("UPSTREAM_ERROR", Map.of(
                            "upstreamStatus", e.getStatusCode().value(),
                            "upstreamBody", e.getResponseBodyAsString())));

        } catch (RestClientException e) {
            // Covers ResourceAccessException (I/O errors) and body-extraction errors.
            Throwable root = rootCause(e);

            if (root instanceof SocketTimeoutException) {
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                        .body(body("TIMEOUT", Map.of("error", message(root))));
            }
            if (root instanceof SocketException || root instanceof EOFException) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(body("CONNECTION_RESET", Map.of("error", message(root))));
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(body("MALFORMED_OR_DECODE_ERROR", Map.of("error", message(e))));
        }
    }

    private static Map<String, Object> body(String outcome, Map<String, Object> extra) {
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", outcome);
        result.putAll(extra);
        return result;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
