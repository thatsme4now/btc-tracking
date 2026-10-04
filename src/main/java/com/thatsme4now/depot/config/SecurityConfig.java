package com.thatsme4now.depot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import com.thatsme4now.depot.security.LoginAuthFailureHandler;
import com.thatsme4now.depot.security.LoginAuthSuccessHandler;
import com.thatsme4now.depot.security.LoginGateAuthorizationManager;

import lombok.RequiredArgsConstructor;

/**
 * Wires up the optional, app-wide password login (see AppSettings#loginPasswordHash for the
 * on/off switch and PasswordLoginAuthenticationProvider for the actual check).
 *
 * Key design points, spelled out here since none of this could be compile-tested before deploy:
 * <ul>
 *   <li>Whether login is required at all is decided fresh per-request by
 *       {@link LoginGateAuthorizationManager} against the live database value — not baked into
 *       this static config — so toggling it in Settings takes effect immediately, no restart.</li>
 *   <li>{@link PasswordLoginAuthenticationProvider} is registered as a bean, which makes Spring
 *       Boot's security auto-configuration back off from creating its own default in-memory user
 *       (the "Using generated security password" startup log line) — this IS the app's only
 *       authentication mechanism.</li>
 *   <li>CSRF stays fully enabled (not disabled) — protects the ~40+ existing POST/PUT/DELETE
 *       fetch() calls across the app's JS once an authenticated session exists to attack. The
 *       token is exposed via a JS-readable cookie (XSRF-TOKEN) and echoed back by every fetch()
 *       call automatically — see /js/csrf.js, loaded first on every page. CsrfTokenRequestAttributeHandler
 *       (the plain, non-XOR handler) is set explicitly to disable Spring Security's newer deferred
 *       token loading: with the default (XOR) handler the cookie is only written once something
 *       explicitly resolves the token, which a plain cookie-reading fetch() wrapper never does on
 *       its own — this makes the cookie always present eagerly on every response instead.</li>
 *   <li>Session is the plain default (in-memory HttpSession) — intentionally NOT "remember me":
 *       restarting the app (or closing the browser) requires logging in again.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final LoginGateAuthorizationManager loginGateAuthorizationManager;
    private final LoginAuthSuccessHandler loginAuthSuccessHandler;
    private final LoginAuthFailureHandler loginAuthFailureHandler;

    // Note: PasswordLoginAuthenticationProvider is picked up automatically as an
    // AuthenticationProvider bean via its own @Component annotation — no need to re-declare it
    // here. (Deliberately not redeclaring it as a second @Bean: that would register the same
    // provider twice in the ProviderManager's chain, and a failed login could then run through
    // authenticate() — and so LoginRateLimiter#registerFailure() — more than once per attempt.)

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Login page itself, plus everything a browser needs to render it (or any other
                // page) before authentication has happened — static assets are never sensitive.
                .requestMatchers(
                    "/login",
                    "/css/**", "/js/**", "/i18n/**", "/docs/**", "/vendor/**",
                    "/favicon.ico", "/favicon-32.png", "/apple-touch-icon.png", "/sats.png"
                ).permitAll()
                // Everything else: gated live against AppSettings#loginPasswordHash — see
                // LoginGateAuthorizationManager. Permits everything when login is disabled.
                .anyRequest().access(loginGateAuthorizationManager)
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .successHandler(loginAuthSuccessHandler)
                .failureHandler(loginAuthFailureHandler)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            );

        return http.build();
    }
}
