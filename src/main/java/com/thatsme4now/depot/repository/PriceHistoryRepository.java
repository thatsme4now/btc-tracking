package com.thatsme4now.depot.repository;

import com.thatsme4now.depot.entity.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    List<PriceHistory> findByTickerOrderByDateAsc(String ticker);

    Optional<PriceHistory> findTopByTickerOrderByDateDesc(String ticker);

    boolean existsByTickerAndDate(String ticker, LocalDate date);
}
