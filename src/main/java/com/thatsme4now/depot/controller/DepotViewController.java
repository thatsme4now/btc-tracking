package com.thatsme4now.depot.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.thatsme4now.depot.dto.PositionDTO;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.service.DepotService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Controller
@RequestMapping("/depot")
@RequiredArgsConstructor
public class DepotViewController {

    private final DepotService depotService;

    @GetMapping("/")
    public String root() {
        return "redirect:/depot";
    }

    @GetMapping
    public String overview(Model model, HttpServletRequest request) {
        // Read currency from cookie (set by JS when user changes setting)
        String currency = depotService.readCookie(request, "depot-currency", "EUR");

        List<PositionDTO> positions = depotService.getAllPositions(currency);

        BigDecimal totalValue = positions.stream()
            .map(PositionDTO::getTotalValue)
            .filter(v -> v != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal invested = positions.stream()
            .map(PositionDTO::getInvested)
            .filter(v -> v != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalBtc = positions.stream()
            .map(PositionDTO::getQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal gainLoss = totalValue.subtract(invested);
        BigDecimal performancePct = invested.compareTo(BigDecimal.ZERO) > 0
            ? gainLoss.divide(invested, 4, RoundingMode.HALF_UP)
                      .multiply(BigDecimal.valueOf(100))
                      .setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        BigDecimal realized = positions.stream()
            .map(PositionDTO::getRealized)
            .filter(v -> v != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalSats = totalBtc.multiply(BigDecimal.valueOf(100_000_000))
            .setScale(0, RoundingMode.HALF_UP);

        model.addAttribute("positions",      positions);
        model.addAttribute("totalValue",     totalValue);
        model.addAttribute("invested",       invested);
        model.addAttribute("realized",       realized);
        model.addAttribute("gainLoss",       gainLoss);
        model.addAttribute("performancePct", performancePct);
        model.addAttribute("totalBtc",       totalBtc);
        model.addAttribute("totalSats",      totalSats);
        model.addAttribute("currency",       currency);

        // BTC price for header badge – from selected currency
		depotService.getCurrentPrice(currency).ifPresentOrElse(p -> {
    			model.addAttribute("btcPrice", p.getPrice());
    			model.addAttribute("btcPriceDate", p.getPriceDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")));
    	}, () -> model.addAttribute("btcPrice", new BigDecimal(0L)));
        
        // Flag: no price available for selected currency
        boolean noPriceAvailable = positions.stream()
            .allMatch(p -> p.getCurrentPrice() == null);
        model.addAttribute("noPriceAvailable", noPriceAvailable);

        model.addAttribute("transactionCount", depotService.getTransactionCount());
        return "depot/overview";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("position", new Position());
        return "depot/position-form";
    }

    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable(name = "id") Long id, Model model) {
        Position p = depotService.getPosition(id)
            .orElseThrow(() -> new IllegalArgumentException("Position not found: " + id));
        model.addAttribute("position", p);
        return "depot/position-form";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute Position position) {
        depotService.save(position);
        return "redirect:/depot";
    }

    @GetMapping("/delete/{id}")
    public String delete(@PathVariable(name = "id") Long id) {
        depotService.delete(id);
        return "redirect:/depot";
    }
}