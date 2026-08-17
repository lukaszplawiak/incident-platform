package com.incidentplatform.escalation.domain;

import com.incidentplatform.shared.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * <h2>Fixed: no optimistic locking (backlog #38)</h2>
 * See migration V5's comment for the full account. In short:
 * {@code EscalationScheduler.checkAndEscalate()} (its own scheduled
 * thread) and {@code EscalationService.cancelEscalation()} (the Kafka
 * listener thread, on an incoming ack event) could both read and write
 * the same row concurrently with no conflict detection — ShedLock only
 * protects against multiple *instances* of the scheduler, not against
 * the Kafka consumer thread racing it within the same instance. The new
 * {@link #version} field, combined with JPA's standard optimistic
 * locking, makes that race detectable instead of silently resolving to
 * whichever write happens to commit last.
 */
@Entity
@Table(
        name = "escalation_tasks",
        indexes = {
                @Index(name = "idx_escalation_tasks_pending",
                        columnList = "scheduled_escalation_at"),
                @Index(name = "idx_escalation_tasks_tenant_id",
                        columnList = "tenant_id")
        }
)
public class EscalationTask {

    public static final int TIMEOUT_CRITICAL = 5;
    public static final int TIMEOUT_HIGH     = 15;
    public static final int TIMEOUT_MEDIUM   = 30;
    public static final int TIMEOUT_LOW      = 60;

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    /**
     * Team responsible for this incident — copied from Incident.team_id
     * at task creation. Used by EscalationScheduler to call oncall-service:
     * GET /oncall/current?teamId={teamId}&role=PRIMARY
     * Null = no team assigned, on-call routing skipped with warning.
     */
    @Column(name = "team_id")
    private UUID teamId;

    @NotNull
    @Column(name = "incident_opened_at", nullable = false, updatable = false)
    private Instant incidentOpenedAt;

    @NotNull
    @Column(name = "scheduled_escalation_at", nullable = false)
    private Instant scheduledEscalationAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EscalationTaskStatus status;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false)
    private Severity severity;

    @NotNull
    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @NotNull
    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Retry tracking (backlog #41) — see migration V6's comment for the
     * full account. Incremented only by {@link #recordFailedAttempt},
     * called from {@code EscalationScheduler.checkAndEscalate()}'s
     * generic failure catch — never for a task skipped due to a detected
     * {@code OptimisticLockingFailureException} (backlog #38), since
     * that's an expected, correct outcome, not a failure.
     */
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    protected EscalationTask() {}

    public static EscalationTask createLevel1(UUID incidentId,
                                              String tenantId,
                                              UUID teamId,
                                              Instant incidentOpenedAt,
                                              Severity severity,
                                              String title) {
        final int timeoutMinutes = resolveTimeout(severity);
        return create(incidentId, tenantId, teamId, incidentOpenedAt,
                severity, title, 1, timeoutMinutes);
    }

    public static EscalationTask createLevel2(UUID incidentId,
                                              String tenantId,
                                              UUID teamId,
                                              Instant level1EscalatedAt,
                                              Severity severity,
                                              String title) {
        final int timeoutMinutes = resolveTimeout(severity);
        return create(incidentId, tenantId, teamId, level1EscalatedAt,
                severity, title, 2, timeoutMinutes);
    }

    private static EscalationTask create(UUID incidentId,
                                         String tenantId,
                                         UUID teamId,
                                         Instant startAt,
                                         Severity severity,
                                         String title,
                                         int escalationLevel,
                                         int timeoutMinutes) {
        final EscalationTask task = new EscalationTask();
        task.id = UUID.randomUUID();
        task.incidentId = incidentId;
        task.tenantId = tenantId;
        task.teamId = teamId;
        task.incidentOpenedAt = startAt;
        task.scheduledEscalationAt = startAt.plusSeconds(timeoutMinutes * 60L);
        task.status = EscalationTaskStatus.PENDING;
        task.severity = severity;
        task.title = title;
        task.escalationLevel = escalationLevel;
        task.createdAt = Instant.now();
        task.updatedAt = Instant.now();
        return task;
    }

    public static int resolveTimeout(Severity severity) {
        return switch (severity) {
            case CRITICAL -> TIMEOUT_CRITICAL;
            case HIGH     -> TIMEOUT_HIGH;
            case MEDIUM   -> TIMEOUT_MEDIUM;
            case LOW      -> TIMEOUT_LOW;
        };
    }

    public void markEscalated() {
        this.status = EscalationTaskStatus.ESCALATED;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = EscalationTaskStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    /**
     * Records a failed escalation attempt — status stays PENDING (the
     * task will be retried on the next scheduler poll cycle, matching
     * the platform's general "retry indefinitely, never give up on a
     * real escalation" philosophy — see {@code EscalationTaskStatus}'s
     * sibling in {@code IncidentEventOutboxStatus} for the same reasoning
     * applied to Kafka publishing). Called only for genuine failures —
     * see this field's own Javadoc for why an optimistic lock conflict
     * must NOT call this.
     */
    public void recordFailedAttempt(String errorMessage) {
        this.retryCount++;
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
    }

    public boolean isPending() {
        return EscalationTaskStatus.PENDING.equals(this.status);
    }

    public boolean isMaxLevel() {
        return this.escalationLevel >= 2;
    }

    public boolean isDueForEscalation() {
        return isPending() && Instant.now().isAfter(scheduledEscalationAt);
    }

    public UUID getId()                       { return id; }
    public UUID getIncidentId()               { return incidentId; }
    public String getTenantId()               { return tenantId; }
    public UUID getTeamId()                   { return teamId; }
    public Instant getIncidentOpenedAt()      { return incidentOpenedAt; }
    public Instant getScheduledEscalationAt() { return scheduledEscalationAt; }
    public EscalationTaskStatus getStatus()   { return status; }
    public Severity getSeverity()             { return severity; }
    public String getTitle()                  { return title; }
    public int getEscalationLevel()           { return escalationLevel; }
    public Instant getCreatedAt()             { return createdAt; }
    public Instant getUpdatedAt()             { return updatedAt; }
    public long getVersion()                  { return version; }
    public int getRetryCount()                { return retryCount; }
    public String getErrorMessage()           { return errorMessage; }
}