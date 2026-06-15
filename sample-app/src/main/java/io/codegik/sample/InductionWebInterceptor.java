package io.codegik.sample;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Copies the inbound {@code x-induction-test-profile} header into
 * {@link InductionContext} for the duration of the request, then clears it. This
 * is the only place the app reads the induction header on the inbound side.
 */
public class InductionWebInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String profile = request.getHeader(InductionHeaders.PROFILE);
        if (profile != null && !profile.isBlank()) {
            InductionContext.setProfile(profile);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        InductionContext.clear();
    }
}
