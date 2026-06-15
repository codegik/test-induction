package io.codegik.sample;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * Charge a payment. Pass {@code x-induction-test-profile} to trigger a
     * behavior registered in the sidecar for the active profile; omit it to call
     * the configured payment URL normally.
     */
    @PostMapping("/pay")
    public ResponseEntity<Map<String, Object>> pay(
            @RequestBody PayRequest request,
            @RequestHeader(value = InductionHeaders.PROFILE, required = false) String profile) {
        return service.charge(request, profile);
    }
}
