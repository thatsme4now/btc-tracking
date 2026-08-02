package com.thatsme4now.depot.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Einfache Historie abgeschlossener CSV-Imports (3-Step-Import-Assistent),
 * angezeigt als eigene Kachel auf der Übersicht. Ein Eintrag wird beim
 * finalen Bestätigen (Step 2 → Step 3) angelegt.
 */
@Data
@Entity
@Table(name = "import_history",
       indexes = {
           @Index(name = "idx_ih_imported_at", columnList = "imported_at")
       })
public class ImportHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt = LocalDateTime.now();

    @Column(nullable = false, length = 255)
    private String filename;

    /** Ursprüngliche Zeilenzahl der Datei (Datenzeilen, ohne Header). */
    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    /** Tatsächlich in die transaction-Tabelle übernommene Zeilen. */
    @Column(name = "imported_rows", nullable = false)
    private int importedRows;

    /** Davon als Duplikat markiert (informativ, wurden trotzdem importiert). */
    @Column(name = "duplicate_rows", nullable = false)
    private int duplicateRows;

    /** Technisch fehlgeschlagen oder wegen bereits existierender transactionId übersprungen. */
    @Column(name = "error_rows", nullable = false)
    private int errorRows;
}
