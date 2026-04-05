package com.incidentplatform.notification.repository;

import com.incidentplatform.notification.domain.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationLogRepository
        extends JpaRepository<NotificationLog, UUID> {

    List<NotificationLog> findByIncidentIdAndTenantIdOrderBySentAtDesc(
            UUID incidentId, String tenantId);
}