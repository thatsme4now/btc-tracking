package com.thatsme4now.depot.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.thatsme4now.depot.BaseWebIntegrationTest;
import com.thatsme4now.depot.service.AppLockService;

class AppLockControllerTest extends BaseWebIntegrationTest {

    private static final Path LOCK_FILE = Path.of("btc-tracking.lock");

    @Autowired
    private AppLockService appLockService;

    @AfterEach
    void cleanUp() throws IOException {
        // AppLockService's rate limiter is in-memory state on the shared singleton
        // bean (same instance across all test classes in this run) and survives
        // @Transactional rollback, so it must be reset explicitly between tests.
        appLockService.reset("delete");
        Files.deleteIfExists(LOCK_FILE);
    }

    @Test
    void status_falseByDefault() throws Exception {
        mockMvc.perform(get("/api/btc-tracking/lock/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
    }

    @Test
    void lock_thenStatus_reflectsLocked() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("password", "pw12345", "passwordConfirm", "pw12345"));

        mockMvc.perform(post("/api/btc-tracking/lock").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true));

        mockMvc.perform(get("/api/btc-tracking/lock/status"))
                .andExpect(jsonPath("$.locked").value(true));
    }

    @Test
    void lock_withMismatchedPasswords_returnsBadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("password", "pw1", "passwordConfirm", "pw2"));

        mockMvc.perform(post("/api/btc-tracking/lock").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void unlock_withCorrectPassword_succeeds() throws Exception {
        String lockBody = objectMapper.writeValueAsString(Map.of("password", "pw12345", "passwordConfirm", "pw12345"));
        mockMvc.perform(post("/api/btc-tracking/lock").contentType(MediaType.APPLICATION_JSON).content(lockBody))
                .andExpect(status().isOk());

        String unlockBody = objectMapper.writeValueAsString(Map.of("password", "pw12345"));
        mockMvc.perform(post("/api/btc-tracking/unlock").contentType(MediaType.APPLICATION_JSON).content(unlockBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
    }

    @Test
    void unlock_withWrongPassword_returnsBadRequest() throws Exception {
        String lockBody = objectMapper.writeValueAsString(Map.of("password", "pw12345", "passwordConfirm", "pw12345"));
        mockMvc.perform(post("/api/btc-tracking/lock").contentType(MediaType.APPLICATION_JSON).content(lockBody))
                .andExpect(status().isOk());

        String unlockBody = objectMapper.writeValueAsString(Map.of("password", "wrong-password"));
        mockMvc.perform(post("/api/btc-tracking/unlock").contentType(MediaType.APPLICATION_JSON).content(unlockBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reset_withWrongConfirmationText_returnsBadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("confirm", "yes"));

        mockMvc.perform(post("/api/btc-tracking/lock/reset").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reset_withCorrectConfirmationText_succeeds() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("confirm", "delete"));

        mockMvc.perform(post("/api/btc-tracking/lock/reset").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(true));
    }
}
