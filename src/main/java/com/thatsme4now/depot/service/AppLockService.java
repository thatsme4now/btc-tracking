package com.thatsme4now.depot.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    public boolean isLocked() {
        return Files.exists(LOCK_FILE);
    }

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
     * "Passwort vergessen"-Reset: Die verschlüsselte Lock-Datei kann ohne
     * Passwort nicht entschlüsselt werden, die darin gesicherten Daten sind
     * damit unwiderruflich verloren. Löscht die Lock-Datei und leert
     * vorsorglich nochmal alle Tabellen (falls durch eine ältere Lock-Datei
     * oder einen inkonsistenten Zwischenzustand noch Reste vorhanden wären),
     * sodass die App danach wie eine frische Installation dasteht.
     *
     * Erwartet den literalen Bestätigungstext "delete" (sprachunabhängig,
     * siehe applock.reset.* im Frontend) als zusätzliche Absicherung gegen
     * versehentliche/direkte API-Aufrufe — die App hat keine eigene
     * Login-Authentifizierung, die UI-Bestätigung allein ist keine echte
     * Sicherheitsgrenze.
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