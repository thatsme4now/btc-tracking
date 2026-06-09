package com.thatsme4now.depot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "current_price")
@IdClass(CurrentPriceId.class)
public class CurrentPrice {

    @Id
    @Column(length = 20)
    private String ticker;

    @Id
    @Column(length = 10)
    private String currency;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal price;

    @Column(name = "price_date", nullable = false)
    private LocalDate priceDate;

    @Column(name = "loaded_at")
    private LocalDateTime loadedAt = LocalDateTime.now();
}