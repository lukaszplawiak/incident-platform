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

    /**
     * Per-channel idempotency check. {@code escalationLevel} is part of the
     * key so a level-2 escalation is not mistaken for the level-1 send that
     * used the same channel (see {@code V5} migration); it is {@code 0} for
     * every event type that is not an escalation. {@code tenantId} keeps the
     * check tenant-scoped.
     */
    boolean existsByIncidentIdAndTenantIdAndEventTypeAndEscalationLevelAndChannel(
            UUID incidentId, String tenantId, String eventType,
            int escalationLevel, String channel);

}