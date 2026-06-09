package com.thatsme4now.depot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "price_history",
       uniqueConstraints = @UniqueConstraint(columnNames = {"ticker", "date"}))
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String ticker;

    @Column(nullable = false)
    private LocalDate date;

    @Column(precision = 14, scale = 4)
    private BigDecimal open;

    @Column(precision = 14, scale = 4)
    private BigDecimal high;

    @Column(precision = 14, scale = 4)
    private BigDecimal low;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal close;

    private Long volume;

    @Column(name = "loaded_at")
    private LocalDateTime loadedAt = LocalDateTime.now();
}
