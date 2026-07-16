package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.TenantSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantSettingsRepository extends JpaRepository<TenantSettings, String> {
    // findById(tenantId) inherited from JpaRepository — sufficient for now
}