package com.thatsme4now.depot.security;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * In-memory, single-instance rate limiting for the optional password login — mirrors
 * {@link com.thatsme4now.depot.service.AppLockService}'s brute-force protection (3 failed
 * attempts → lockout), but with an escalating lockout duration: every 3 more failures while
 * already having triggered at least one lockout doubles the wait, capped at 15 minutes, so
 * repeated guessing gets progressively slower rather than just being reset every 30 seconds.
 * A single successful login resets everything back to the base 30s/3-attempt state.
 *
 * Deliberately the same simple AtomicInteger + volatile-epoch pattern as AppLockService: this
 * is a single-user, single-JVM app, so there is no need for a distributed rate limiter.
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private static final int  ATTEMPTS_PER_ROUND    = 3;
    private static final long BASE_LOCKOUT_MILLIS    = 30_000L;   // 30s
    private static final long MAX_LOCKOUT_MILLIS      = 900_000L; // 15min

    private final AtomicInteger failedAttempts = new AtomicInteger(0);
    private volatile long lockedUntilEpoch    = 0L;
    private volatile long nextLockoutMillis   = BASE_LOCKOUT_MILLIS;

    /** Throws {@link LockedException} (with the remaining wait baked into the message) if currently locked out. */
    public void checkNotLocked() {
        long remaining = getRemainingSeconds();
        if (remaining > 0) {
            throw new LockedException("Too many failed login attempts. Try again in " + remaining + "s.");
        }
    }

    /** Registers a failed attempt; every 3rd failure (per round) triggers/escalates a lockout. */
    public void registerFailure() {
        if (failedAttempts.incrementAndGet() >= ATTEMPTS_PER_ROUND) {
            lockedUntilEpoch = System.currentTimeMillis() + nextLockoutMillis;
            failedAttempts.set(0);
            log.warn("Login rate limit triggered — locked for {}ms", nextLockoutMillis);
            nextLockoutMillis = Math.min(nextLockoutMillis * 2, MAX_LOCKOUT_MILLIS);
        }
    }

    /** Resets all rate-limit state back to the base — called after a successful login. */
    public void registerSuccess() {
        failedAttempts.set(0);
        lockedUntilEpoch = 0L;
        nextLockoutMillis = BASE_LOCKOUT_MILLIS;
    }

    /** Seconds remaining until a currently-active lockout expires (0 if not locked). */
    public long getRemainingSeconds() {
        long remainingMillis = lockedUntilEpoch - System.currentTimeMillis();
        return remainingMillis > 0 ? (remainingMillis / 1000) + 1 : 0;
    }
}
