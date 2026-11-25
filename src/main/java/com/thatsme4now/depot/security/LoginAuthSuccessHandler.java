package com.thatsme4now.depot.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** Resets the rate limiter on a successful login and sends the user to the app root. */
@Component
@RequiredArgsConstructor
public class LoginAuthSuccessHandler implements AuthenticationSuccessHandler {

    private final LoginRateLimiter rateLimiter;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException, ServletException {
        rateLimiter.registerSuccess();
        response.sendRedirect(request.getContextPath() + "/");
    }
}
