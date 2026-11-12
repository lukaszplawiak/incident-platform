package com.incidentplatform.oncall.repository;

import com.incidentplatform.oncall.domain.OncallRole;
import com.incidentplatform.oncall.domain.OncallSchedule;
import com.incidentplatform.oncall.domain.OncallScheduleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres test of {@link OncallScheduleRepository#findByTenantIdAndOptionalStatus}
 * — backlog #0-189. The only test of its caller ({@code OncallScheduleServiceTest})
 * mocks the repository, so nothing ran the JPQL
 * {@code (:status IS NULL OR s.status = :status)} against a database. A
 * {@code null} bound there is where Hibernate 6 and the PostgreSQL driver can
 * disagree on the parameter type; a wrong binding fails only at run time, on
 * the path without a status.
 *
 * <p>Dataset (rolled back per test): the queried tenant holds ACTIVE at T1 and
 * T3, SUPERSEDED at T2 and CANCELLED at T4; the other tenant holds ACTIVE,
 * SUPERSEDED and CANCELLED at T5..T7, later than all of the queried tenant's
 * rows, so a missing tenant predicate would put one of them at the head of
 * every page, and an ignored status filter would put the CANCELLED T4 row at
 * the head of the ACTIVE page.
 */
@SpringBootTest
@Testcontainers
@Transactional
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding"
})
@DisplayName("OncallScheduleRepository.findByTenantIdAndOptionalStatus — real Postgres")
class OncallScheduleOptionalStatusIntegrationTest {

    // postgres:16-alpine — same image as docker/docker-compose.yml and the
    // other repository integration tests of this module.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withReuse(true);

    private static final String QUERIED = "tenant-a";
    private static final String OTHER = "tenant-b";

    private static final Instant T1 = Instant.parse("2026-06-01T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-02T09:00:00Z");
    private static final Instant T3 = Instant.parse("2026-06-03T09:00:00Z");
    private static final Instant T4 = Instant.parse("2026-06-04T09:00:00Z");
    private static final Instant T5 = Instant.parse("2026-06-05T09:00:00Z");
    private static final Instant T6 = Instant.parse("2026-06-06T09:00:00Z");
    private static final Instant T7 = Instant.parse("2026-06-07T09:00:00Z");

    @Autowired
    private OncallScheduleRepository repository;

    private OncallSchedule activeT1;
    private OncallSchedule supersededT2;
    private OncallSchedule activeT3;
    private OncallSchedule cancelledT4;
    private OncallSchedule otherActive;
    private OncallSchedule otherSuperseded;
    private OncallSchedule otherCancelled;

    @BeforeEach
    void seed() {
        activeT1 = save(QUERIED, OncallScheduleStatus.ACTIVE, T1);
        supersededT2 = save(QUERIED, OncallScheduleStatus.SUPERSEDED, T2);
        activeT3 = save(QUERIED, OncallScheduleStatus.ACTIVE, T3);
        cancelledT4 = save(QUERIED, OncallScheduleStatus.CANCELLED, T4);
        otherActive = save(OTHER, OncallScheduleStatus.ACTIVE, T5);
        otherSuperseded = save(OTHER, OncallScheduleStatus.SUPERSEDED, T6);
        otherCancelled = save(OTHER, OncallScheduleStatus.CANCELLED, T7);
    }

    /** Own team per row, so the ACTIVE-scoped EXCLUDE constraint never fires. */
    private OncallSchedule save(String tenantId, OncallScheduleStatus status, Instant startsAt) {
        final OncallSchedule schedule = OncallSchedule.create(
                tenantId, UUID.randomUUID(), "user-1", "Jan Kowalski", "jan@example.com",
                "+48100200300", "U0123456789", OncallRole.PRIMARY,
                startsAt, startsAt.plusSeconds(3600), "Optional status test schedule");
        repository.saveAndFlush(schedule);
        if (status == OncallScheduleStatus.SUPERSEDED) {
            schedule.markSuperseded();
            repository.saveAndFlush(schedule);
        } else if (status == OncallScheduleStatus.CANCELLED) {
            schedule.cancel();
            repository.saveAndFlush(schedule);
        }
        return schedule;
    }

    @Test
    @DisplayName("status=ACTIVE returns only the tenant's ACTIVE rows, latest first")
    void activeStatusReturnsOnlyTheTenantsActiveRows() {
        final Page<OncallSchedule> page = repository.findByTenantIdAndOptionalStatus(
                QUERIED, OncallScheduleStatus.ACTIVE, PageRequest.of(0, 20));

        assertThat(page).isNotNull();
        assertThat(page.getContent()).extracting(OncallSchedule::getId)
                .containsExactly(activeT3.getId(), activeT1.getId());
        assertThat(page.getContent()).allSatisfy(s -> {
            assertThat(s.getTenantId()).isEqualTo(QUERIED);
            assertThat(s.getStatus()).isEqualTo(OncallScheduleStatus.ACTIVE);
        });
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("status=null binds without error and returns every status of the tenant only")
    void nullStatusReturnsEveryStatusOfTheTenant() {
        final Page<OncallSchedule> page = repository.findByTenantIdAndOptionalStatus(
                QUERIED, null, PageRequest.of(0, 20));

        assertThat(page).isNotNull();
        assertThat(page.getContent()).extracting(OncallSchedule::getId)
                .containsExactly(cancelledT4.getId(), activeT3.getId(),
                        supersededT2.getId(), activeT1.getId());
        assertThat(page.getContent()).extracting(OncallSchedule::getStatus)
                .contains(OncallScheduleStatus.ACTIVE, OncallScheduleStatus.SUPERSEDED,
                        OncallScheduleStatus.CANCELLED);
        assertThat(page.getContent()).extracting(OncallSchedule::getTenantId)
                .doesNotContain(OTHER);
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("page of 1 with status: head is the latest matching row, total counts all matches")
    void pageOfOneWithStatusHoldsLatestMatchingRowAndFullTotal() {
        final Page<OncallSchedule> page = repository.findByTenantIdAndOptionalStatus(
                QUERIED, OncallScheduleStatus.ACTIVE, PageRequest.of(0, 1));

        assertThat(page.getContent()).extracting(OncallSchedule::getId)
                .containsExactly(activeT3.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("page of 1 without status: head is the tenant's latest row, total counts its rows")
    void pageOfOneWithoutStatusHoldsLatestRowOfTenantAndFullTotal() {
        final Page<OncallSchedule> page = repository.findByTenantIdAndOptionalStatus(
                QUERIED, null, PageRequest.of(0, 1));

        assertThat(page.getContent()).extracting(OncallSchedule::getId)
                .containsExactly(cancelledT4.getId());
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("positive control: the other tenant sees exactly its own three rows")
    void otherTenantSeesOnlyItsOwnRows() {
        final Page<OncallSchedule> page = repository.findByTenantIdAndOptionalStatus(
                OTHER, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(OncallSchedule::getId)
                .containsExactly(otherCancelled.getId(), otherSuperseded.getId(),
                        otherActive.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
    }
}
