package com.incidentplatform.incident.repository;

import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.shared.domain.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncidentRepository
        extends JpaRepository<Incident, UUID>,
        JpaSpecificationExecutor<Incident> {

    Optional<Incident> findByIdAndTenantId(UUID id, String tenantId);

    Page<Incident> findByTenantIdOrderByCreatedAtDesc(String tenantId,
                                                      Pageable pageable);

    @Query("""
            SELECT COUNT(i) > 0
            FROM Incident i
            WHERE i.tenantId = :tenantId
              AND i.alertFingerprint = :alertFingerprint
              AND i.status != com.incidentplatform.incident.domain.IncidentStatus.CLOSED
            """)
    boolean existsActiveByTenantIdAndAlertFingerprint(
            @Param("tenantId") String tenantId,
            @Param("alertFingerprint") String alertFingerprint);

    @Query("""
            SELECT i
            FROM Incident i
            WHERE i.alertFingerprint = :alertFingerprint
              AND i.tenantId = :tenantId
              AND i.status != com.incidentplatform.incident.domain.IncidentStatus.CLOSED
            ORDER BY i.createdAt DESC
            """)
    Optional<Incident> findActiveByAlertFingerprintAndTenantId(
            @Param("alertFingerprint") String alertFingerprint,
            @Param("tenantId") String tenantId);

    long countByTenantIdAndStatusAndSeverity(String tenantId,
                                             IncidentStatus status,
                                             Severity severity);
}