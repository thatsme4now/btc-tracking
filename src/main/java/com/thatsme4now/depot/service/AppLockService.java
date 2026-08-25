package com.thatsme4now.depot.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Password-based app lock: encrypts all data into a single lock file on
 * disk (clearing the live tables) and decrypts it back on unlock. Includes
 * simple in-memory rate limiting against brute-force unlock attempts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppLockService {

    private static final Path LOCK_FILE = Path.of("btc-tracking.lock");
    private static final int  MAX_ATTEMPTS   = 3;
    private static final long LOCKOUT_MILLIS = 30_000L;

    private final DataExportService dataExportService;

    private final AtomicInteger failedAttempts   = new AtomicInteger(0);
    private volatile long       lockedUntilEpoch = 0L;

    /** Returns whether the app is currently locked (the lock file exists). */
    public boolean isLocked() {
        return Files.exists(LOCK_FILE);
    }

    /**
     * Encrypts all current data into the lock file and clears the live
     * tables, leaving the app inaccessible until {@link #unlock} is called.
     */
    public void lock(String password, String passwordConfirm) {
        if (isLocked()) {
            throw new IllegalStateException("App is already locked.");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password required.");
        }
        if (!password.equals(passwordConfirm)) {
            throw new IllegalArgumentException("Passwords do not match.");
        }

        byte[] encrypted = dataExportService.exportFull(password);
        try {
            Files.write(LOCK_FILE, encrypted);
        } catch (IOException e) {
            throw new RuntimeException("Could not write lock file: " + e.getMessage(), e);
        }

        dataExportService.clearAll();
        log.info("App locked — data encrypted to {}", LOCK_FILE.toAbsolutePath());
    }

    /**
     * Decrypts the lock file with the given password, restores its data into
     * the live tables, and removes the lock file. Rate-limited: repeated
     * failures temporarily lock out further attempts.
     */
    public UnlockResult unlock(String password) {
        checkRateLimit();

        if (!isLocked()) {
            throw new IllegalStateException("App is not locked.");
        }

        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(LOCK_FILE);
        } catch (IOException e) {
            throw new RuntimeException("Could not read lock file: " + e.getMessage(), e);
        }

        DataExportService.ImportSummary summary;
        try {
            summary = dataExportService.importFull(fileBytes, password);
        } catch (CsvEncryptionService.EncryptionException e) {
            registerFailedAttempt();
            throw e;
        }

        try {
            Files.delete(LOCK_FILE);
        } catch (IOException e) {
            throw new RuntimeException("Unlock succeeded but lock file could not be removed: " + e.getMessage(), e);
        }

        resetRateLimit();
        log.info("App unlocked — {} positions, {} transactions restored", summary.positions, summary.transactions);
        return new UnlockResult(summary.positions, summary.transactions);
    }

    /**
     * "Forgot password" reset: the encrypted lock file can't be decrypted
     * without the password, so its data is unrecoverably lost. Deletes the
     * lock file and clears all tables again defensively, leaving the app
     * like a fresh install. Requires the literal confirmation text "delete"
     * (language-independent, see applock.reset.* in the frontend) as a
     * safeguard against accidental direct API calls. Since the optional
     * app-wide login (see SecurityConfig) is off by default and this
     * confirmation text is the only thing standing between an unauthenticated
     * caller and this method in that default state, it is not a real security
     * boundary by itself — only enabling login makes reaching this endpoint at
     * all require being authenticated first.
     */
    public void reset(String confirm) {
        if (!"delete".equals(confirm)) {
            throw new IllegalArgumentException("Confirmation text must be 'delete'.");
        }
        if (isLocked()) {
            try {
                Files.delete(LOCK_FILE);
            } catch (IOException e) {
                throw new RuntimeException("Could not delete lock file: " + e.getMessage(), e);
            }
        }
        dataExportService.clearAll();
        resetRateLimit();
        log.warn("App reset — lock file discarded and all data cleared (password forgotten).");
    }

    // ── Rate limiting (in-memory, single-user) ────────────────

    private void checkRateLimit() {
        long now = System.currentTimeMillis();
        if (now < lockedUntilEpoch) {
            long remaining = (lockedUntilEpoch - now) / 1000 + 1;
            throw new RateLimitException(remaining);
        }
    }

    private void registerFailedAttempt() {
        if (failedAttempts.incrementAndGet() >= MAX_ATTEMPTS) {
            lockedUntilEpoch = System.currentTimeMillis() + LOCKOUT_MILLIS;
            failedAttempts.set(0);
        }
    }

    private void resetRateLimit() {
        failedAttempts.set(0);
        lockedUntilEpoch = 0L;
    }

    public record UnlockResult(int positions, int transactions) {}

    public static class RateLimitException extends RuntimeException {
        public final long remainingSeconds;
        public RateLimitException(long remainingSeconds) {
            super("Too many failed attempts.");
            this.remainingSeconds = remainingSeconds;
        }
    }
}