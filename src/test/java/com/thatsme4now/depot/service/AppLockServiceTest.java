package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.thatsme4now.depot.BaseIntegrationTest;
import com.thatsme4now.depot.TestFixtures;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;
import com.thatsme4now.depot.service.AppLockService.UnlockResult;

/**
 * {@link AppLockService} writes a real lock file next to the working
 * directory (not just DB rows), so this class always removes it afterwards
 * regardless of transaction rollback.
 */
class AppLockServiceTest extends BaseIntegrationTest {

    private static final Path LOCK_FILE = Path.of("btc-tracking.lock");

    @Autowired
    private AppLockService appLockService;

    @Autowired
    private DepotService depotService;

    @AfterEach
    void cleanUp() throws IOException {
        // appLockService.reset() clears the DB, deletes the lock file if present,
        // AND resets the in-memory rate limiter — the latter is plain singleton
        // bean state that survives @Transactional rollback, so it must be reset
        // explicitly or failed-attempt counts leak into the next test.
        appLockService.reset("delete");
        Files.deleteIfExists(LOCK_FILE);
    }

    @Test
    void isLocked_falseByDefault() {
        assertThat(appLockService.isLocked()).isFalse();
    }

    @Test
    void lockThenUnlock_roundTripsData() {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        appLockService.lock("s3cr3t-pw", "s3cr3t-pw");
        assertThat(appLockService.isLocked()).isTrue();
        assertThat(Files.exists(LOCK_FILE)).isTrue();
        assertThat(depotService.getAllTransactions()).isEmpty();

        UnlockResult result = appLockService.unlock("s3cr3t-pw");

        assertThat(result.positions()).isEqualTo(1);
        assertThat(result.transactions()).isEqualTo(1);
        assertThat(appLockService.isLocked()).isFalse();
        assertThat(depotService.getAllTransactions()).hasSize(1);
    }

    @Test
    void lock_withMismatchedPasswords_throws() {
        assertThatThrownBy(() -> appLockService.lock("password1", "password2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lock_whenAlreadyLocked_throws() {
        appLockService.lock("pw", "pw");
        assertThatThrownBy(() -> appLockService.lock("pw", "pw"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unlock_withWrongPassword_throwsAndRegistersFailedAttempt() {
        appLockService.lock("correct-pw", "correct-pw");

        assertThatThrownBy(() -> appLockService.unlock("wrong-pw"))
                .isInstanceOf(CsvEncryptionService.EncryptionException.class);
        // app stays locked after a failed attempt
        assertThat(appLockService.isLocked()).isTrue();
    }

    @Test
    void unlock_afterThreeFailedAttempts_rateLimits() {
        appLockService.lock("correct-pw", "correct-pw");

        for (int i = 0; i < 3; i++) {
            try {
                appLockService.unlock("wrong-pw");
            } catch (CsvEncryptionService.EncryptionException ignored) {
                // expected
            }
        }

        assertThatThrownBy(() -> appLockService.unlock("correct-pw"))
                .isInstanceOf(AppLockService.RateLimitException.class);
    }

    @Test
    void unlock_whenNotLocked_throws() {
        assertThatThrownBy(() -> appLockService.unlock("any-pw"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reset_requiresLiteralConfirmationText() {
        assertThatThrownBy(() -> appLockService.reset("yes"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reset_clearsDataAndRemovesLockFile_evenWhenLocked() {
        Position p = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(p, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));
        appLockService.lock("pw", "pw");

        appLockService.reset("delete");

        assertThat(appLockService.isLocked()).isFalse();
        assertThat(Files.exists(LOCK_FILE)).isFalse();
        assertThat(depotService.getAllTransactions()).isEmpty();
    }
}
