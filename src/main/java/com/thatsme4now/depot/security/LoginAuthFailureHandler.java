package com.thatsme4now.depot.security;

import java.io.IOException;

import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Redirects a failed login attempt back to /login with an error indicator the page can render a
 * message for. Distinguishes a rate-limit lockout (shows the remaining wait, does NOT count as a
 * further failed attempt — checkNotLocked() already rejected it before any password was checked)
 * from a plain wrong password (registers the failure, which may itself trigger a new lockout).
 * The wrong password itself is never included in the redirect or logged anywhere.
 */
@Component
@RequiredArgsConstructor
public class LoginAuthFailureHandler implements AuthenticationFailureHandler {

    private final LoginRateLimiter rateLimiter;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException, ServletException {
        String query;
        if (exception instanceof LockedException) {
            query = "error=locked&seconds=" + rateLimiter.getRemainingSeconds();
        } else {
            rateLimiter.registerFailure();
            query = "error=bad";
        }
        response.sendRedirect(request.getContextPath() + "/login?" + query);
    }
}
