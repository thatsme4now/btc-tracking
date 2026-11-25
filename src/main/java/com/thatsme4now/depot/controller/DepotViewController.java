package com.thatsme4now.depot.controller;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.thatsme4now.depot.dto.PositionDTO;
import com.thatsme4now.depot.service.DepotService;
import com.thatsme4now.depot.service.ImportWizardService;
import com.thatsme4now.depot.service.ImportWizardService.UploadResult;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * MVC controller that renders the application's Thymeleaf pages: the main
 * overview/transaction-management page, the three visualization pages
 * (flow, holdings, yearly), and the 3-step CSV import wizard.
 */
@Controller
@RequiredArgsConstructor
public class DepotViewController {

    private final DepotService depotService;
    private final ImportWizardService importWizardService;

    /**
     * Exposed to every view rendered by this controller (navbar.html reads it to show/hide the
     * Logout button) — true only when a login password is currently configured. Deliberately not
     * exposed for the /login page itself, which never shows the navbar.
     */
    @ModelAttribute("loginEnabled")
    public boolean loginEnabled() {
        String hash = depotService.getAppSettings().getLoginPasswordHash();
        return hash != null && !hash.isBlank();
    }

    /** Redirects the app root to the holdings visualization page. */
    @GetMapping("/")
    public String root() {
    	return "redirect:/btc-tracking/holdings";
    }

    /**
     * Renders the standalone login page (no navbar). If login isn't currently configured there is
     * nothing to log into, so this redirects to the app root instead of showing a pointless form —
     * e.g. a stale bookmark to /login after the password was removed in Settings.
     */
    @GetMapping("/login")
    public String login(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "seconds", required = false) Long seconds,
            @RequestParam(value = "logout", required = false) String logout,
            Model model) {
        if (!loginEnabled()) {
            return "redirect:/";
        }
        model.addAttribute("loginError", error);
        model.addAttribute("lockedSeconds", seconds);
        model.addAttribute("loggedOut", logout != null);
        return "depot/login";
    }

    /** Renders the flow diagram (Sankey) visualization page. */
    @GetMapping("/btc-tracking/flow")
    public String flow() {
        return "depot/flow";
    }

    /** Renders the holdings visualization page. */
    @GetMapping("/btc-tracking/holdings")
    public String holdings() {
        return "depot/holdings";
    }

    /** Renders the yearly overview visualization page. */
    @GetMapping("/btc-tracking/yearly")
    public String yearly() {
        return "depot/yearly";
    }

    /**
     * Renders the main overview page: positions, the current BTC price for
     * the selected display currency, and the recent import history.
     */
    @GetMapping("/btc-tracking")
    public String overview(Model model, HttpServletRequest request) {
        // currency is set by JS via cookie when the user changes the setting
        String currency = depotService.readCookie(request, "depot-currency", "EUR");

        List<PositionDTO> positions = depotService.getAllPositions(currency);

        model.addAttribute("positions",      positions);
        model.addAttribute("currency",       currency);

        // BTC price for the header badge, in the selected currency
		depotService.getCurrentPrice(currency).ifPresentOrElse(p -> {
    			model.addAttribute("btcPrice", p.getPrice());
    			model.addAttribute("btcPriceDate",
    					p.getPriceDate().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
                model.addAttribute("currentPrice", p.getPrice());

    	}, () -> {
    		model.addAttribute("btcPrice", new BigDecimal(0L));
    		model.addAttribute("btcPriceDate", "");
            model.addAttribute("currentPrice", new BigDecimal(0L));

    	});

        boolean noPriceAvailable = positions.stream()
            .allMatch(p -> p.getCurrentPrice() == null);
        model.addAttribute("noPriceAvailable", noPriceAvailable);

        model.addAttribute("importHistory", importWizardService.getHistory(20));

        return "depot/overview";
    }

    // ── Import wizard (3 steps) ─────────────────────────────────────────

    /**
     * Import wizard step 1: parses the uploaded file server-side and renders
     * the mapping page with the raw data embedded in it. No redirect — the
     * parsed data lives only in this response (not persisted server-side);
     * mapping changes then run entirely client-side, see import-mapping.js.
     */
    @PostMapping("/btc-tracking/import/mapping")
    public String importMapping(@RequestParam("file") MultipartFile file, Model model) {
        UploadResult result;
        try {
            result = importWizardService.parseUpload(file);
        } catch (IOException e) {
            model.addAttribute("uploadError", e.getMessage());
            model.addAttribute("filename", "");
            model.addAttribute("headers", List.of());
            model.addAttribute("rows", List.of());
            return "depot/import-mapping";
        }
        model.addAttribute("filename", result.filename);
        model.addAttribute("headers", result.headers);
        model.addAttribute("rows", result.rows);
        return "depot/import-mapping";
    }

    /** Import wizard step 2: renders the staging table for review before commit. */
    @GetMapping("/btc-tracking/import/review")
    public String importReview() {
        return "depot/import-review";
    }

    /** Import wizard step 3: renders the import result summary. */
    @GetMapping("/btc-tracking/import/status")
    public String importStatus() {
        return "depot/import-status";
    }

}