package com.thatsme4now.depot.repository;

import com.thatsme4now.depot.entity.MonthlyPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MonthlyPriceRepository extends JpaRepository<MonthlyPrice, Long> {

    Optional<MonthlyPrice> findByTickerAndYearAndMonthAndCurrency(String ticker, Integer year, Integer month, String currency);

    List<MonthlyPrice> findByTickerAndCurrencyOrderByYearAscMonthAsc(String ticker, String currency);

    boolean existsByTickerAndYearAndMonthAndCurrency(String ticker, Integer year, Integer month, String currency);
}
