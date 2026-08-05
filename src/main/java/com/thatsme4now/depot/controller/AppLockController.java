package com.thatsme4now.depot.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.thatsme4now.depot.service.AppLockService;
import com.thatsme4now.depot.service.CsvEncryptionService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/btc-tracking")
@RequiredArgsConstructor
public class AppLockController {

    private final AppLockService appLockService;

    @GetMapping("/lock/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("locked", appLockService.isLocked()));
    }

    @PostMapping("/lock")
    public ResponseEntity<Map<String, Object>> lock(@RequestBody LockRequest req) {
        try {
            appLockService.lock(req.getPassword(), req.getPasswordConfirm());
            return ResponseEntity.ok(Map.of("locked", true));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/unlock")
    public ResponseEntity<Map<String, Object>> unlock(@RequestBody UnlockRequest req) {
        try {
            AppLockService.UnlockResult result = appLockService.unlock(req.getPassword());
            return ResponseEntity.ok(Map.of(
                "locked", false,
                "positions", result.positions(),
                "transactions", result.transactions()
            ));
        } catch (AppLockService.RateLimitException e) {
            return ResponseEntity.status(429).body(Map.of(
                "error", "Rate limited",
                "remainingSeconds", e.remainingSeconds
            ));
        } catch (CsvEncryptionService.EncryptionException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/lock/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestBody ResetRequest req) {
        try {
            appLockService.reset(req.getConfirm());
            return ResponseEntity.ok(Map.of("reset", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @lombok.Data
    public static class LockRequest {
        private String password;
        private String passwordConfirm;
    }

    @lombok.Data
    public static class UnlockRequest {
        private String password;
    }

    @lombok.Data
    public static class ResetRequest {
        private String confirm;
    }
}