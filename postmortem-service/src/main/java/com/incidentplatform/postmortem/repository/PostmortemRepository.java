package com.incidentplatform.postmortem.repository;

import com.incidentplatform.postmortem.domain.Postmortem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostmortemRepository extends JpaRepository<Postmortem, UUID> {

    Optional<Postmortem> findByIncidentIdAndTenantId(UUID incidentId, String tenantId);

    List<Postmortem> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    boolean existsByIncidentId(UUID incidentId);

    List<Postmortem> findByStatus(String status);

    @Query("SELECT p FROM Postmortem p WHERE p.status = 'FAILED' AND p.retryCount < :maxRetryAttempts")
    List<Postmortem> findFailedWithRemainingRetries(@Param("maxRetryAttempts") int maxRetryAttempts);
}