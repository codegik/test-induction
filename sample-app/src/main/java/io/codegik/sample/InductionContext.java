package io.codegik.sample;

/**
 * Holds the induction profile for the current request thread. It is populated
 * from the inbound {@code x-induction-test-profile} header and read by the
 * outbound HTTP routing so the app's business code never has to thread the
 * profile through its method signatures.
 */
public final class InductionContext {

    private static final ThreadLocal<String> PROFILE = new ThreadLocal<>();

    private InductionContext() {
    }

    public static void setProfile(String profile) {
        PROFILE.set(profile);
    }

    /** The active profile, or {@code null} when this is a normal (non-induced) request. */
    public static String profile() {
        return PROFILE.get();
    }

    public static void clear() {
        PROFILE.remove();
    }
}
