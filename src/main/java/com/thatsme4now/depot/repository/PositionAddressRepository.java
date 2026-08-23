package com.thatsme4now.depot.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.thatsme4now.depot.entity.PositionAddress;

@Repository
public interface PositionAddressRepository extends JpaRepository<PositionAddress, Long> {

    List<PositionAddress> findByPositionIdOrderByIdAsc(Long positionId);

    void deleteByPositionId(Long positionId);
}
