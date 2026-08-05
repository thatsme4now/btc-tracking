package com.thatsme4now.depot.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.thatsme4now.depot.dto.ImportHistoryDTO;
import com.thatsme4now.depot.dto.ImportStagingRowDTO;
import com.thatsme4now.depot.service.DepotService;
import com.thatsme4now.depot.service.ImportWizardService;
import com.thatsme4now.depot.service.ImportWizardService.ConfirmResult;
import com.thatsme4now.depot.service.ImportWizardService.StageResult;
import com.thatsme4now.depot.service.ImportWizardService.StagingUpdateRequest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * REST-Endpunkte für den 3-Step-Import-Assistenten (Step 2 "Review" und die
 * Übergänge dazwischen). Step 1 (Mapping-Vorschau) läuft clientseitig gegen
 * die in der Seite eingebetteten Rohdaten, siehe DepotViewController#importMapping.
 */
@RestController
@RequestMapping("/api/btc-tracking/import")
@RequiredArgsConstructor
public class ImportRestController {

    private final ImportWizardService importWizardService;
    private final DepotService        depotService;

    private String currentCurrency(HttpServletRequest request) {
        return depotService.readCookie(request, "depot-currency", "EUR");
    }

    /** Step 1 → Step 2: fertig gemappte Zeilen in die Staging-Tabelle übernehmen. */
    @PostMapping("/stage")
    public ResponseEntity<Map<String, Object>> stage(
            @RequestBody StageRequest req, HttpServletRequest request) {
        StageResult result = importWizardService.stageRows(req.getRows(), currentCurrency(request));
        return ResponseEntity.ok(Map.of(
            "staged", result.staged,
            "errorCount", result.errorCount,
            "skippedNoBtc", result.skippedNoBtc
        ));
    }

    @GetMapping("/staging")
    public List<ImportStagingRowDTO> listStaging() {
        return importWizardService.listStaging();
    }

    @PutMapping("/staging/{id}")
    public ResponseEntity<ImportStagingRowDTO> updateStaging(
            @PathVariable("id") Long id,
            @RequestBody StagingUpdateRequest req,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok(importWizardService.updateStaging(id, req, currentCurrency(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/staging/{id}")
    public ResponseEntity<Void> deleteStaging(@PathVariable("id") Long id) {
        importWizardService.deleteStaging(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/staging/bulk-pair")
    public ResponseEntity<Map<String, Object>> bulkPair(@RequestBody BulkIdsRequest req) {
        if (req.getIds() == null || req.getIds().size() < 2 || req.getIds().size() % 2 != 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Even number of IDs required"));
        }
        int paired = importWizardService.bulkPairStaging(req.getIds());
        return ResponseEntity.ok(Map.of("paired", paired));
    }

    @PostMapping("/staging/bulk-remove-transfer")
    public ResponseEntity<Map<String, Object>> bulkRemoveTransfer(@RequestBody BulkIdsRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids"));
        }
        int removed = importWizardService.bulkRemoveTransferStaging(req.getIds());
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    @PostMapping("/staging/bulk-exrate")
    public ResponseEntity<Map<String, Object>> bulkExRate(
            @RequestBody BulkExRateRequest req, HttpServletRequest request) {
        if (req.getIds() == null || req.getIds().isEmpty() || req.getExchangeRate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids or exchangeRate"));
        }
        int updated = importWizardService.bulkExRateStaging(req.getIds(), req.getExchangeRate(), currentCurrency(request));
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @DeleteMapping("/staging/bulk")
    public ResponseEntity<Map<String, Object>> bulkDelete(@RequestBody BulkIdsRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids"));
        }
        int deleted = importWizardService.bulkDeleteStaging(req.getIds());
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @PostMapping("/staging/bulk-move")
    public ResponseEntity<Map<String, Object>> bulkMove(@RequestBody BulkMoveRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()
                || req.getTargetExchange() == null || req.getTargetExchange().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids or targetExchange"));
        }
        int moved = importWizardService.bulkMoveStaging(req.getIds(), req.getTargetExchange());
        return ResponseEntity.ok(Map.of("moved", moved));
    }

    @PostMapping("/staging/bulk-solo-transfer")
    public ResponseEntity<Map<String, Object>> bulkSoloTransfer(@RequestBody BulkIdsRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids"));
        }
        try {
            int marked = importWizardService.bulkSoloTransferStaging(req.getIds());
            return ResponseEntity.ok(Map.of("marked", marked));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Abbrechen auf Step 1/2 — leert die Staging-Tabelle. */
    @PostMapping("/cancel")
    public ResponseEntity<Void> cancel() {
        importWizardService.cancelImport();
        return ResponseEntity.noContent().build();
    }

    /** Step 2 → Step 3: finaler Commit. */
    @PostMapping("/confirm")
    public ResponseEntity<ConfirmResult> confirm(@RequestBody ConfirmRequest req) {
        ConfirmResult result = importWizardService.confirmImport(req.getFilename(), req.getTotalRows());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history")
    public List<ImportHistoryDTO> history(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        return importWizardService.getHistory(limit);
    }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<Void> deleteHistory(
            @PathVariable("id") Long id,
            @RequestParam(name = "deleteTransactions", defaultValue = "false") boolean deleteTransactions) {
        try {
            importWizardService.deleteHistory(id, deleteTransactions);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Inner DTOs ────────────────────────────────────────────────────────────

    @lombok.Data
    public static class StageRequest {
        private List<DepotRestController.MappedRow> rows;
    }

    @lombok.Data
    public static class BulkIdsRequest {
        private List<Long> ids;
    }

    @lombok.Data
    public static class BulkExRateRequest {
        private List<Long> ids;
        private java.math.BigDecimal exchangeRate;
    }

    @lombok.Data
    public static class BulkMoveRequest {
        private List<Long> ids;
        private String targetExchange;
    }

    @lombok.Data
    public static class ConfirmRequest {
        private String filename;
        private Integer totalRows;
    }
}
