package com.incidentplatform.oncall.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "oncall_schedules",
        indexes = {
                @Index(name = "idx_oncall_schedules_tenant_time",
                        columnList = "tenant_id, starts_at, ends_at"),
                @Index(name = "idx_oncall_schedules_user",
                        columnList = "tenant_id, user_id")
        }
)
public class OncallSchedule {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @NotBlank
    @Column(name = "user_id", nullable = false)
    private String userId;

    @NotBlank
    @Column(name = "user_name", nullable = false)
    private String userName;

    @Email
    @NotBlank
    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "slack_user_id")
    private String slackUserId;

    @NotBlank
    @Column(name = "role", nullable = false)
    private String role;

    @NotNull
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @NotNull
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OncallSchedule() {}

    public static OncallSchedule create(String tenantId,
                                        String userId,
                                        String userName,
                                        String email,
                                        String phone,
                                        String slackUserId,
                                        String role,
                                        Instant startsAt,
                                        Instant endsAt,
                                        String notes) {
        final OncallSchedule schedule = new OncallSchedule();
        schedule.id = UUID.randomUUID();
        schedule.tenantId = tenantId;
        schedule.userId = userId;
        schedule.userName = userName;
        schedule.email = email;
        schedule.phone = phone;
        schedule.slackUserId = slackUserId;
        schedule.role = role;
        schedule.startsAt = startsAt;
        schedule.endsAt = endsAt;
        schedule.notes = notes;
        schedule.createdAt = Instant.now();
        schedule.updatedAt = Instant.now();
        return schedule;
    }

    public boolean isActiveAt(Instant moment) {
        return !moment.isBefore(startsAt) && moment.isBefore(endsAt);
    }

    public boolean isPrimary()   { return "PRIMARY".equals(this.role); }
    public boolean isSecondary() { return "SECONDARY".equals(this.role); }
    public boolean isManager()   { return "MANAGER".equals(this.role); }

    public UUID getId()           { return id; }
    public String getTenantId()   { return tenantId; }
    public String getUserId()     { return userId; }
    public String getUserName()   { return userName; }
    public String getEmail()      { return email; }
    public String getPhone()      { return phone; }
    public String getSlackUserId(){ return slackUserId; }
    public String getRole()       { return role; }
    public Instant getStartsAt()  { return startsAt; }
    public Instant getEndsAt()    { return endsAt; }
    public String getNotes()      { return notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}