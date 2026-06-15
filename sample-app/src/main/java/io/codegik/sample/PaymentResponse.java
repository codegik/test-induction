package io.codegik.sample;

/** The successful response shape expected from the payment API. */
public record PaymentResponse(String paymentId, String status) {
}
