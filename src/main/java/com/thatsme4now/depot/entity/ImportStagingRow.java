package com.thatsme4now.depot.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Zwischenspeicher für eine einzelne Zeile eines laufenden CSV-Imports (Step 2
 * "Review" des 3-Step-Import-Assistenten). Wird beim Übergang Step 1 → Step 2
 * aus den vom Client gemappten Zeilen befüllt (siehe ImportWizardService) und
 * nach Bestätigen/Abbrechen bzw. vor jedem neuen Datei-Upload komplett geleert.
 *
 * Bewusst KEINE Foreign Keys / Enum-Constraints auf type — Zeilen mit
 * fehlerhaftem Mapping (ungültiges Datum, unbekannte Währung, o.ä.) müssen
 * trotzdem gespeichert werden können (siehe hasError/errorReason), damit der
 * Nutzer sie in der Review-Ansicht sehen und reparieren kann, statt dass sie
 * beim Import still verschwinden.
 */
@Data
@Entity
@Table(name = "import_staging_row",
       indexes = {
           @Index(name = "idx_isr_row_index", columnList = "row_index")
       })
public class ImportStagingRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Chronologische Reihenfolge innerhalb des Imports (für stabile Sortierung/Pairing). */
    @Column(name = "row_index", nullable = false)
    private Integer rowIndex;

    /** Ursprünglicher CSV-Typ-Wert vor dem Typ-Remapping auf Step 1 (Debug/Anzeige). */
    @Column(name = "raw_typ", length = 50)
    private String rawTyp;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TransactionType type;

    @Column(name = "position_label", length = 100)
    private String positionLabel;

    /** Rohwert des Datums, falls Parsen fehlgeschlagen ist — sonst gleich dateParsed formatiert. */
    @Column(name = "date_raw", length = 64)
    private String dateRaw;

    @Column(name = "date_parsed")
    private LocalDateTime dateParsed;

    @Column(precision = 18, scale = 8)
    private BigDecimal quantity;

    @Column(name = "quantity_fiat", precision = 14, scale = 2)
    private BigDecimal quantityFiat;

    @Column(name = "price_per_btc", precision = 14, scale = 2)
    private BigDecimal pricePerBtc;

    @Column(length = 10)
    private String currency;

    @Column(name = "exchange_rate", precision = 14, scale = 6)
    private BigDecimal exchangeRate;

    @Column(precision = 18, scale = 8)
    private BigDecimal fees;

    @Column(name = "fees_currency", length = 10)
    private String feesCurrency;

    @Column(length = 255)
    private String comment;

    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Column(name = "transfer_id", length = 36)
    private String transferId;

    @Column(name = "is_duplicate", nullable = false)
    private boolean duplicate = false;

    /** Fremdwährung + kein Wechselkurs hinterlegt — nur bei BUY/SELL relevant. */
    @Column(name = "is_fx_warning", nullable = false)
    private boolean fxWarning = false;

    /** Zeile konnte nicht vollständig/korrekt gemappt werden — siehe errorReason. */
    @Column(name = "has_error", nullable = false)
    private boolean hasError = false;

    @Column(name = "error_reason", length = 255)
    private String errorReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
