package io.codegik.sample;

import java.math.BigDecimal;

/** Incoming charge request and the body sent to the payment API. */
public record PayRequest(BigDecimal amount, String currency) {
}
