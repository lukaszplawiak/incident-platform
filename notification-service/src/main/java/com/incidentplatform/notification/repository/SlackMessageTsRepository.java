package com.incidentplatform.notification.repository;

import com.incidentplatform.notification.domain.SlackMessageTs;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SlackMessageTsRepository
        extends JpaRepository<SlackMessageTs, SlackMessageTs.Key> {

    Optional<SlackMessageTs> findByIdIncidentIdAndIdChannel(
            UUID incidentId, String channel);

    List<SlackMessageTs> findByIdIncidentId(UUID incidentId);

    void deleteByIdIncidentId(UUID incidentId);

    /**
     * Deletes rows older than {@code threshold} — the cleanup path for
     * incidents that were never acknowledged via Slack (e.g. acknowledged
     * through the web UI instead), so their entries would otherwise never
     * be removed by {@link #deleteByIdIncidentId}.
     */
    @Modifying
    @Query("DELETE FROM SlackMessageTs t WHERE t.createdAt < :threshold")
    int deleteByCreatedAtBefore(@Param("threshold") Instant threshold);
}