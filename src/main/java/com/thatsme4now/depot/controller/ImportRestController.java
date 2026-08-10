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
 * REST endpoints for the 3-step import wizard: step 2 ("review" of the
 * staged rows) and the transitions around it. Step 1 (mapping preview) runs
 * entirely client-side against the raw data embedded in the page, see
 * {@link com.thatsme4now.depot.controller.DepotViewController#importMapping}.
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

    /** Step 1 → step 2: commits the client-mapped rows into the staging table. */
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

    /** Lists all currently staged rows for the review step. */
    @GetMapping("/staging")
    public List<ImportStagingRowDTO> listStaging() {
        return importWizardService.listStaging();
    }

    /** Updates a single staged row's fields. */
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

    /** Deletes a single staged row. */
    @DeleteMapping("/staging/{id}")
    public ResponseEntity<Void> deleteStaging(@PathVariable("id") Long id) {
        importWizardService.deleteStaging(id);
        return ResponseEntity.noContent().build();
    }

    /** Pairs an even-numbered selection of staged transfer rows with each other. */
    @PostMapping("/staging/bulk-pair")
    public ResponseEntity<Map<String, Object>> bulkPair(@RequestBody BulkIdsRequest req) {
        if (req.getIds() == null || req.getIds().size() < 2 || req.getIds().size() % 2 != 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Even number of IDs required"));
        }
        int paired = importWizardService.bulkPairStaging(req.getIds());
        return ResponseEntity.ok(Map.of("paired", paired));
    }

    /** Clears the transfer pairing of the selected staged rows. */
    @PostMapping("/staging/bulk-remove-transfer")
    public ResponseEntity<Map<String, Object>> bulkRemoveTransfer(@RequestBody BulkIdsRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids"));
        }
        int removed = importWizardService.bulkRemoveTransferStaging(req.getIds());
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    /** Applies a new exchange rate to the selected staged rows. */
    @PostMapping("/staging/bulk-exrate")
    public ResponseEntity<Map<String, Object>> bulkExRate(
            @RequestBody BulkExRateRequest req, HttpServletRequest request) {
        if (req.getIds() == null || req.getIds().isEmpty() || req.getExchangeRate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids or exchangeRate"));
        }
        int updated = importWizardService.bulkExRateStaging(req.getIds(), req.getExchangeRate(), currentCurrency(request));
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    /** Deletes the selected staged rows. */
    @DeleteMapping("/staging/bulk")
    public ResponseEntity<Map<String, Object>> bulkDelete(@RequestBody BulkIdsRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids"));
        }
        int deleted = importWizardService.bulkDeleteStaging(req.getIds());
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /** Reassigns the selected staged rows to a different wallet/exchange. */
    @PostMapping("/staging/bulk-move")
    public ResponseEntity<Map<String, Object>> bulkMove(@RequestBody BulkMoveRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()
                || req.getTargetExchange() == null || req.getTargetExchange().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing ids or targetExchange"));
        }
        int moved = importWizardService.bulkMoveStaging(req.getIds(), req.getTargetExchange());
        return ResponseEntity.ok(Map.of("moved", moved));
    }

    /** Marks the selected staged transfer rows as solo transfers (no counterpart expected). */
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

    /** Cancels the import wizard at step 1/2 and clears the staging table. */
    @PostMapping("/cancel")
    public ResponseEntity<Void> cancel() {
        importWizardService.cancelImport();
        return ResponseEntity.noContent().build();
    }

    /** Step 2 → step 3: commits all staged rows as real transactions. */
    @PostMapping("/confirm")
    public ResponseEntity<ConfirmResult> confirm(@RequestBody ConfirmRequest req) {
        ConfirmResult result = importWizardService.confirmImport(req.getFilename(), req.getTotalRows());
        return ResponseEntity.ok(result);
    }

    /** Lists the most recent completed imports. */
    @GetMapping("/history")
    public List<ImportHistoryDTO> history(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        return importWizardService.getHistory(limit);
    }

    /** Deletes an import history entry, optionally deleting its transactions too. */
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
