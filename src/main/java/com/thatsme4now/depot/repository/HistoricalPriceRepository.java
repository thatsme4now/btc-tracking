package com.thatsme4now.depot.repository;

import com.thatsme4now.depot.entity.HistoricalPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HistoricalPriceRepository extends JpaRepository<HistoricalPrice, Long> {

    Optional<HistoricalPrice> findByTickerAndYearAndCurrency(String ticker, Integer year, String currency);

    List<HistoricalPrice> findByTicker(String ticker);

    boolean existsByTickerAndYearAndCurrency(String ticker, Integer year, String currency);
}
