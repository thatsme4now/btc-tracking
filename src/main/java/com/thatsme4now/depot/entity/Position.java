package com.thatsme4now.depot.entity;
 
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
 
@Data
@Entity
@Table(name = "position")
public class Position {
 
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
 
    /** Exchange or wallet name, e.g. "Binance", "Ledger" */
    @Column(nullable = false, length = 100)
    private String label;
 
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PositionType type;
 
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
 
    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();
 
    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}