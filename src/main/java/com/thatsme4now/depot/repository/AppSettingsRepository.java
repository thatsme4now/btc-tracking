package com.thatsme4now.depot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.thatsme4now.depot.entity.AppSettings;

@Repository
public interface AppSettingsRepository extends JpaRepository<AppSettings, Long> {
}
