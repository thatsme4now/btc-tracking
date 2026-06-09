package com.thatsme4now.depot.repository;

import com.thatsme4now.depot.entity.CurrentPrice;
import com.thatsme4now.depot.entity.CurrentPriceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CurrentPriceRepository extends JpaRepository<CurrentPrice, CurrentPriceId> {

    Optional<CurrentPrice> findByTickerAndCurrency(String ticker, String currency);
}