package com.thatsme4now.depot.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.thatsme4now.depot.entity.ImportStagingRow;
import com.thatsme4now.depot.entity.TransactionType;

@Repository
public interface ImportStagingRowRepository extends JpaRepository<ImportStagingRow, Long> {

    List<ImportStagingRow> findAllByOrderByRowIndexAsc();

    long countByDateParsedAndTypeAndQuantity(LocalDateTime dateParsed, TransactionType type, BigDecimal quantity);

    /** Wie {@link #countByDateParsedAndTypeAndQuantity}, aber ohne die Zeile
     *  selbst mitzuzählen — für das Neuberechnen des Duplikat-Flags beim
     *  Einzel-Edit (sonst zählt jede Zeile sich immer selbst als "Duplikat"). */
    long countByDateParsedAndTypeAndQuantityAndIdNot(LocalDateTime dateParsed, TransactionType type,
                                                      BigDecimal quantity, Long id);
}
