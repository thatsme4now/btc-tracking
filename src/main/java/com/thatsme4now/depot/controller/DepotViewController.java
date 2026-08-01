package com.thatsme4now.depot.controller;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.thatsme4now.depot.dto.PositionDTO;
import com.thatsme4now.depot.service.DepotService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class DepotViewController {

    private final DepotService depotService;

    @GetMapping("/")
    public String root() {
    	return "redirect:/btc-tracking";
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

        return "depot/overview";
    }

}