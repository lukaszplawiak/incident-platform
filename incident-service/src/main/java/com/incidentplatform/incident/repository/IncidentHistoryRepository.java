package com.incidentplatform.incident.repository;

import com.incidentplatform.incident.domain.IncidentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentHistoryRepository extends JpaRepository<IncidentHistory, UUID> {

    List<IncidentHistory> findByIncidentIdAndTenantIdOrderByChangedAtAsc(
            UUID incidentId, String tenantId);
}