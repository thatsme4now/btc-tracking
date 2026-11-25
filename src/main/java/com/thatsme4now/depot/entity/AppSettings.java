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
 *
 * mempoolHost/mempoolPort: Host/Port einer selbst gehosteten mempool-Instanz
 * (z.B. die mempool-App im eigenen Umbrel/LAN), über die optional der
 * aktuelle BTC-Preis abgerufen und fehlende Monats-Ultimo-Kurse in der
 * Jahresansicht befüllt werden können — siehe MempoolPriceService. Beide
 * NULL/leer = Funktion deaktiviert (Standard, Opt-in). Der Backend-Server
 * (nicht der Browser) ruft diesen Host/Port auf.
 *
 * loginPasswordHash: BCrypt hash of the optional, app-wide login password
 * (a single global password, no username) — see SecurityConfig and
 * PasswordLoginAuthenticationProvider. NULL/blank = login disabled
 * (default, opt-in) — the app is then reachable without any access gate,
 * as before. The plaintext password itself is never stored or logged,
 * only this hash. Deliberately kept separate from the Lock mechanism
 * (AppLockService): its exportFull()/clearAll() do NOT cover app_settings,
 * i.e. this field survives every lock/unlock cycle unchanged.
 */
@Data
@Entity
@Table(name = "app_settings")
public class AppSettings {

    @Id
    private Long id = 1L;

    @Column(name = "tax_holding_period_cutoff_date")
    private LocalDate taxHoldingPeriodCutoffDate;

    @Column(name = "mempool_host", length = 255)
    private String mempoolHost;

    @Column(name = "mempool_port")
    private Integer mempoolPort;

    @Column(name = "login_password_hash", length = 255)
    private String loginPasswordHash;
}
