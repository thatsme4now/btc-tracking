package com.thatsme4now.depot.security;

import java.util.function.Supplier;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import com.thatsme4now.depot.service.DepotService;

import lombok.RequiredArgsConstructor;

/**
 * Gates every request not otherwise permitted in SecurityConfig: if no login password is
 * currently configured, everything is allowed (the app behaves exactly as it did before this
 * feature existed); if one is configured, the request must carry an authenticated session.
 *
 * This is checked fresh against the database on every request (same as every other optional
 * setting in this app, e.g. the mempool integration — no caching layer exists anywhere here, and
 * a single-row lookup is cheap) rather than once at startup, so enabling/disabling login in
 * Settings takes effect immediately without an app restart.
 */
@Component
@RequiredArgsConstructor
public class LoginGateAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final DepotService depotService;

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        String hash = depotService.getAppSettings().getLoginPasswordHash();
        boolean loginRequired = hash != null && !hash.isBlank();
        if (!loginRequired) {
            return new AuthorizationDecision(true);
        }

        Authentication auth = authentication.get();
        boolean authenticated = auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
        return new AuthorizationDecision(authenticated);
    }
}
