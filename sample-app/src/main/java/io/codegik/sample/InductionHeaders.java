package io.codegik.sample;

/**
 * Header names understood by the test-induction sidecar. The sample app sets
 * these on its outbound calls so the sidecar can select the right behavior.
 */
public final class InductionHeaders {

    /** Selects the active test profile (e.g. "payment-timeout"). */
    public static final String PROFILE = "x-induction-test-profile";

    /** Identifies the client type making the call (e.g. "payment-service"). */
    public static final String CALLER = "x-induction-test-caller";

    private InductionHeaders() {
    }
}
