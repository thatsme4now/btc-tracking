package com.thatsme4now.depot.repository;

import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByPositionIdOrderByDateAsc(Long positionId);

    long countByPositionId(Long positionId);

    List<Transaction> findByPositionIdOrderByDateDesc(Long positionId);

    List<Transaction> findByTransferId(String transferId);

    List<Transaction> findByPositionIdAndTypeInOrderByDateAsc(Long positionId, List<TransactionType> types);

    List<Transaction> findAllByOrderByDateDesc();

    Optional<Transaction> findFirstByOrderByDateAsc();

    boolean existsByPositionIdAndDateAndTypeAndQuantity(
            Long positionId, LocalDateTime date, TransactionType type, BigDecimal quantity);
   
    boolean existsByDateAndTypeAndQuantity(
            LocalDateTime date, TransactionType type, BigDecimal quantity);
    
    boolean existsByTransactionId(String transactionId);

    long countByImportHistoryId(Long importHistoryId);

    void deleteByImportHistoryId(Long importHistoryId);

    /** Löst die Verknüpfung auf, ohne die Transaktionen selbst anzurühren —
     *  genutzt beim "nur Eintrag löschen" (Transaktionen bleiben erhalten). */
    @Modifying
    @Query("UPDATE Transaction t SET t.importHistoryId = NULL WHERE t.importHistoryId = :importHistoryId")
    void clearImportHistoryId(@Param("importHistoryId") Long importHistoryId);
}