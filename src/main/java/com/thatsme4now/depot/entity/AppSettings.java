package com.thatsme4now.depot.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Singleton-Zeile (feste id=1) für App-weite Einstellungen, die kein
 * eigenes Repository-Muster rechtfertigen. Analog zu current_price ein
 * einfaches, zweckgebundenes Tabellen-Design statt eines generischen
 * Key-Value-Stores.
 *
 * taxHoldingPeriodCutoffDate: Stichtag, ab dem für NEU angeschaffte Coins
 * (Kaufdatum ≥ Stichtag) die 1-Jahres-Haltefrist-Steuerfreiheit (§23 EStG,
 * rein informativ, keine Steuerberatung) nicht mehr gilt — unabhängig von
 * der Haltedauer. Für Coins mit Kaufdatum vor dem Stichtag gilt weiterhin
 * die bestehende 365-Tage-Regel (Bestandsschutz, analog zur Abgeltungsteuer-
 * Einführung 2009 bei Aktien). NULL = Funktion deaktiviert (Standard).
 */
@Data
@Entity
@Table(name = "app_settings")
public class AppSettings {

    @Id
    private Long id = 1L;

    @Column(name = "tax_holding_period_cutoff_date")
    private LocalDate taxHoldingPeriodCutoffDate;
}
