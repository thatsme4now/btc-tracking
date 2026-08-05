package com.thatsme4now.depot.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import com.thatsme4now.depot.entity.ImportHistory;

@Repository
public interface ImportHistoryRepository extends JpaRepository<ImportHistory, Long> {

    List<ImportHistory> findAllByOrderByImportedAtDesc(Pageable pageable);
}
