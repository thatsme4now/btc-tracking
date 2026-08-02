package com.thatsme4now.depot.controller;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.thatsme4now.depot.dto.PositionDTO;
import com.thatsme4now.depot.service.DepotService;
import com.thatsme4now.depot.service.ImportWizardService;
import com.thatsme4now.depot.service.ImportWizardService.UploadResult;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class DepotViewController {

    private final DepotService depotService;
    private final ImportWizardService importWizardService;

    @GetMapping("/")
    public String root() {
    	return "redirect:/btc-tracking/holdings";
    }
    
    @GetMapping("/btc-tracking/flow")
    public String flow() {
        return "depot/flow";
    }

    @GetMapping("/btc-tracking/holdings")
    public String holdings() {
        return "depot/holdings";
    }

    @GetMapping("/btc-tracking/yearly")
    public String yearly() {
        return "depot/yearly";
    }

    @GetMapping("/btc-tracking")
    public String overview(Model model, HttpServletRequest request) {
        // Read currency from cookie (set by JS when user changes setting)
        String currency = depotService.readCookie(request, "depot-currency", "EUR");

        List<PositionDTO> positions = depotService.getAllPositions(currency);

        model.addAttribute("positions",      positions);
        model.addAttribute("currency",       currency);

        // BTC price for header badge – from selected currency
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
        
        // Flag: no price available for selected currency
        boolean noPriceAvailable = positions.stream()
            .allMatch(p -> p.getCurrentPrice() == null);
        model.addAttribute("noPriceAvailable", noPriceAvailable);

        // Import-Historie-Kachel — einfache Liste, siehe ImportWizardService#getHistory
        model.addAttribute("importHistory", importWizardService.getHistory(20));

        return "depot/overview";
    }

    // ── Import-Assistent (3 Steps) ─────────────────────────────────────────

    /**
     * Step 1: Datei-Upload → serverseitiges Parsen (ersetzt PapaParse für
     * diesen Schritt) → Mapping-Seite mit eingebetteten Rohdaten. Kein Redirect
     * (die Daten leben nur im Response, nicht serverseitig zwischengespeichert) —
     * Mapping-Änderungen laufen danach komplett clientseitig gegen die
     * eingebetteten Daten, siehe import-mapping.js.
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

    @GetMapping("/btc-tracking/import/review")
    public String importReview() {
        return "depot/import-review";
    }

    @GetMapping("/btc-tracking/import/status")
    public String importStatus() {
        return "depot/import-status";
    }

}