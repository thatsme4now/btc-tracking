package com.thatsme4now.depot.repository;

import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByPositionIdOrderByDateAsc(Long positionId);

    List<Transaction> findByPositionIdOrderByDateDesc(Long positionId);

    List<Transaction> findByTransferId(String transferId);

    List<Transaction> findByPositionIdAndTypeInOrderByDateAsc(Long positionId, List<TransactionType> types);

    List<Transaction> findAllByOrderByDateDesc();

//    boolean existsByDateAndTypeAndQuantity(
//    	    LocalDateTime date, TransactionType type, BigDecimal quantity);
    boolean existsByPositionIdAndDateAndTypeAndQuantity(
            Long positionId, LocalDateTime date, TransactionType type, BigDecimal quantity);

}