package com.incidentplatform.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Join entity between {@link Team} and {@link User} — models team membership
 * with a per-team role.
 *
 * <h2>Composite primary key</h2>
 * The PK is {@code (team_id, user_id)} — a user can belong to a team only
 * once (but can belong to multiple teams with different roles).
 *
 * <h2>Team role</h2>
 * {@link TeamRole} is a team-level role, separate from the tenant-level
 * {@link Role} on {@link UserRole}. A user can be {@code RESPONDER} at the
 * tenant level but {@code MANAGER} in their team.
 */
@Entity
@Table(name = "team_members")
public class TeamMember {

    @EmbeddedId
    private TeamMemberId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("teamId")
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "team_role", nullable = false, length = 30)
    private TeamRole teamRole;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected TeamMember() {}

    public static TeamMember create(Team team, User user, TeamRole role) {
        final TeamMember member = new TeamMember();
        member.id       = new TeamMemberId(team.getId(), user.getId());
        member.team     = team;
        member.user     = user;
        member.teamRole = role;
        member.joinedAt = Instant.now();
        return member;
    }

    public void updateRole(TeamRole newRole) {
        this.teamRole = newRole;
    }

    public TeamMemberId getId()  { return id; }
    public Team getTeam()        { return team; }
    public User getUser()        { return user; }
    public TeamRole getTeamRole() { return teamRole; }
    public Instant getJoinedAt() { return joinedAt; }

    /**
     * Composite primary key — {@code (team_id, user_id)}.
     */
    @Embeddable
    public static class TeamMemberId implements Serializable {

        @Column(name = "team_id")
        private UUID teamId;

        @Column(name = "user_id")
        private UUID userId;

        protected TeamMemberId() {}

        public TeamMemberId(UUID teamId, UUID userId) {
            this.teamId = teamId;
            this.userId = userId;
        }

        public UUID getTeamId() { return teamId; }
        public UUID getUserId() { return userId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TeamMemberId other)) return false;
            return java.util.Objects.equals(teamId, other.teamId)
                    && java.util.Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(teamId, userId);
        }
    }
}