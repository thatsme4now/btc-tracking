package com.thatsme4now.depot.repository;

import com.thatsme4now.depot.entity.Position;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {
	
	 Optional<Position> findByLabel(String label);
}

