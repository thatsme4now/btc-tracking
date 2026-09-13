package com.thatsme4now.depot.security;

import java.util.List;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.thatsme4now.depot.service.DepotService;

import lombok.RequiredArgsConstructor;

/**
 * Authenticates against the single, global, optional login password stored (as a BCrypt hash)
 * in {@code app_settings.login_password_hash} — see {@link com.thatsme4now.depot.entity.AppSettings}.
 * There is no username: the login form submits a fixed hidden username value which this provider
 * ignores entirely; only the password is checked.
 *
 * Deliberately implemented as a self-contained {@link AuthenticationProvider} instead of the more
 * common {@code UserDetailsService} + {@code DaoAuthenticationProvider} pairing: that combination
 * has its own internal exception-translation behavior (e.g. hiding "user not found" as
 * "bad credentials") that isn't relevant here (there's only ever one implicit user) and that this
 * class avoids depending on by fully controlling the authenticate() method itself — including the
 * rate-limit check, which runs first and blocks the password comparison entirely while locked out.
 *
 * Registering this bean makes Spring Boot's security auto-configuration back off from creating its
 * own default in-memory user (the "using generated security password" log line) — see SecurityConfig.
 */
@Component
@RequiredArgsConstructor
public class PasswordLoginAuthenticationProvider implements AuthenticationProvider {

    /** Fixed placeholder principal name — there is no real username, only a password. */
    public static final String PRINCIPAL = "user";

    private final DepotService depotService;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        // Blocks the attempt entirely (without even touching the password) while rate-limited —
        // see LoginRateLimiter for the escalating-lockout logic.
        rateLimiter.checkNotLocked();

        Object credentials = authentication.getCredentials();
        String rawPassword = credentials != null ? credentials.toString() : null;

        String hash = depotService.getAppSettings().getLoginPasswordHash();
        boolean loginConfigured = hash != null && !hash.isBlank();

        if (!loginConfigured || rawPassword == null || rawPassword.isBlank()
                || !passwordEncoder.matches(rawPassword, hash)) {
            throw new BadCredentialsException("Invalid password");
        }

        // authenticated=true via the 3-arg constructor; credentials cleared (null) — the raw
        // password has done its job and is never kept around beyond this method.
        return new UsernamePasswordAuthenticationToken(
                PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
