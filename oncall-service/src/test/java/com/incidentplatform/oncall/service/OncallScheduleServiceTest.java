package com.incidentplatform.oncall.service;

import com.incidentplatform.oncall.domain.OncallRole;
import com.incidentplatform.oncall.domain.OncallSchedule;
import com.incidentplatform.oncall.domain.OncallScheduleStatus;
import com.incidentplatform.oncall.dto.CreateOncallScheduleRequest;
import com.incidentplatform.oncall.dto.CurrentOncallResponse;
import com.incidentplatform.oncall.dto.OncallScheduleDto;
import com.incidentplatform.oncall.dto.SlackUserLookupResponse;
import com.incidentplatform.oncall.dto.UpdateOncallScheduleRequest;
import com.incidentplatform.oncall.repository.OncallScheduleRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("OncallScheduleService")
class OncallScheduleServiceTest {

    @Mock
    private OncallScheduleRepository repository;

    private OncallScheduleService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final Instant STARTS_AT =
            Instant.now().plusSeconds(3600);
    private static final Instant ENDS_AT =
            Instant.now().plusSeconds(3600 * 24 * 7);

    /**
     * Used by every existing test in this file that isn't specifically
     * about the new {@code requireAdminOrTeamManager} authorization logic
     * (see the dedicated {@code TeamManagerAuthorization} nested class
     * below for that) — ROLE_ADMIN always passes the check regardless of
     * teamId, so it's the simplest principal that doesn't interfere with
     * what these tests actually verify (overlap detection, validation,
     * DTO mapping, etc.).
     */
    private static final UserPrincipal ADMIN_PRINCIPAL = new UserPrincipal(
            UUID.randomUUID(), TENANT_ID, "admin@example.com",
            List.of("ROLE_ADMIN"), List.of());

    @BeforeEach
    void setUp() {
        service = new OncallScheduleService(repository);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("should create schedule when no overlap exists")
        void shouldCreateScheduleWhenNoOverlap() {
            // given
            final CreateOncallScheduleRequest request =
                    buildRequest(OncallRole.PRIMARY.name());

            given(repository.existsOverlappingForCreate(
                    eq(TENANT_ID), isNull(), eq(OncallRole.PRIMARY), any(), any()))
                    .willReturn(false);
            given(repository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            // when
            final OncallScheduleDto result =
                    service.create(TENANT_ID, request, ADMIN_PRINCIPAL);

            // then
            assertThat(result.tenantId()).isEqualTo(TENANT_ID);
            assertThat(result.userId()).isEqualTo("user-1");
            // OncallScheduleDto.role() is String (via getRole().name() in from())
            assertThat(result.role()).isEqualTo(OncallRole.PRIMARY.name());
            assertThat(result.email()).isEqualTo("jan@example.com");
            then(repository).should().save(any());
        }

        @Test
        @DisplayName("should save schedule with correct fields")
        void shouldSaveScheduleWithCorrectFields() {
            // given
            final CreateOncallScheduleRequest request =
                    buildRequest(OncallRole.SECONDARY.name());

            given(repository.existsOverlappingForCreate(
                    anyString(), any(), any(), any(), any()))
                    .willReturn(false);
            given(repository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            // when
            service.create(TENANT_ID, request, ADMIN_PRINCIPAL);

            // then
            final ArgumentCaptor<OncallSchedule> captor =
                    ArgumentCaptor.forClass(OncallSchedule.class);
            then(repository).should().save(captor.capture());

            final OncallSchedule saved = captor.getValue();
            assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
            // saved.getRole() returns OncallRole enum — compare with enum constant
            assertThat(saved.getRole()).isEqualTo(OncallRole.SECONDARY);
            assertThat(saved.getSlackUserId()).isEqualTo("U0123456789");
        }

        @Test
        @DisplayName("should throw BusinessException when schedule overlaps existing")
        void shouldThrowWhenOverlap() {
            // given
            final CreateOncallScheduleRequest request =
                    buildRequest(OncallRole.PRIMARY.name());

            given(repository.existsOverlappingForCreate(
                    anyString(), any(), any(), any(), any()))
                    .willReturn(true);

            // when / then
            // BusinessException → GlobalExceptionHandler → 409 Conflict.
            assertThatThrownBy(() -> service.create(TENANT_ID, request, ADMIN_PRINCIPAL))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("overlaps");

            then(repository).should(never()).save(any());
        }

        /**
         * Unit-level coverage for the fix documented in
         * OncallScheduleService.create's inline comment: the application-
         * level existsOverlappingForCreate check is check-then-act, not
         * atomic — a genuine race between two concurrent requests is only
         * actually prevented by the excl_oncall_schedule_overlap database
         * constraint (V4 migration). This test cannot exercise that real
         * constraint (no DB in this test — repository is mocked; see the
         * new backlog item for adding Testcontainers-based integration
         * coverage of the actual constraint). What it does verify: IF the
         * database rejects the insert with a DataIntegrityViolationException
         * (which is what Spring translates a Postgres exclusion_violation
         * into), the service correctly converts that into the same
         * BusinessException.scheduleOverlap the app-level check throws —
         * a clean 409, not an unhandled 500.
         */
        @Test
        @DisplayName("translates a DB-level exclusion-constraint violation into the same 409 as the app-level check")
        void translatesDataIntegrityViolationIntoBusinessException() {
            // given — app-level check finds nothing (simulating the race:
            // another request committed its overlapping schedule between
            // this check and this save())
            final CreateOncallScheduleRequest request =
                    buildRequest(OncallRole.PRIMARY.name());

            given(repository.existsOverlappingForCreate(
                    anyString(), any(), any(), any(), any()))
                    .willReturn(false);
            given(repository.save(any()))
                    .willThrow(new DataIntegrityViolationException(
                            "ERROR: conflicting key value violates exclusion " +
                                    "constraint excl_oncall_schedule_overlap"));

            // when / then
            assertThatThrownBy(() -> service.create(TENANT_ID, request, ADMIN_PRINCIPAL))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("overlaps");
        }


        /**
         * Regression test for the fix documented in
         * OncallScheduleRepository.existsOverlappingForCreate's Javadoc:
         * the overlap check previously didn't filter by teamId at all, so
         * two different teams could never both have a PRIMARY on-call at
         * the same time. This test can't observe the SQL-level teamId
         * filtering directly (the repository call is mocked), but it does
         * verify the service layer actually passes teamId through to the
         * repository — the other half of that fix, in
         * OncallScheduleService.create. Without this, the SQL fix alone
         * would be silently ineffective.
         */
        @Test
        @DisplayName("passes request.teamId() through to the overlap check, not just tenantId/role")
        void passesTeamIdToOverlapCheck() {
            // given
            final UUID teamId = UUID.randomUUID();
            final CreateOncallScheduleRequest request = new CreateOncallScheduleRequest(
                    teamId, "user-1", "Jan Kowalski", "jan@example.com",
                    "+48100200300", "U0123456789", OncallRole.PRIMARY.name(),
                    STARTS_AT, ENDS_AT, "Test schedule"
            );

            given(repository.existsOverlappingForCreate(
                    eq(TENANT_ID), eq(teamId), eq(OncallRole.PRIMARY), any(), any()))
                    .willReturn(false);
            given(repository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            service.create(TENANT_ID, request, ADMIN_PRINCIPAL);

            // then — specifically verifies teamId (not any()) was the
            // actual argument passed, not just that some call happened
            then(repository).should().existsOverlappingForCreate(
                    eq(TENANT_ID), eq(teamId), eq(OncallRole.PRIMARY), any(), any());
        }
    }

    /**
     * Coverage for backlog #43's supersede pattern — this method and
     * {@code UpdateOncallScheduleRequest} did not exist before this fix.
     * See {@code OncallScheduleService.supersede}'s own Javadoc for the
     * full account of why this exists (replacing the previous
     * DELETE-then-POST client-side workaround, which had a real coverage
     * gap between the two calls).
     */
    @Nested
    @DisplayName("supersede")
    class Supersede {

        @Test
        @DisplayName("should mark the old row SUPERSEDED and save both rows")
        void shouldMarkOldSupersededAndSaveBoth() {
            // given
            final OncallSchedule old = buildSchedule(OncallRole.PRIMARY);
            final UpdateOncallScheduleRequest request =
                    buildUpdateRequest(OncallRole.SECONDARY.name());

            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.of(old));
            given(repository.existsOverlapping(
                    eq(TENANT_ID), isNull(), eq(OncallRole.SECONDARY),
                    any(), any(), eq(SCHEDULE_ID)))
                    .willReturn(false);
            given(repository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            final OncallScheduleDto result =
                    service.supersede(SCHEDULE_ID, TENANT_ID, request, ADMIN_PRINCIPAL);

            // then
            assertThat(old.getStatus()).isEqualTo(OncallScheduleStatus.SUPERSEDED);
            assertThat(old.getSupersededAt()).isNotNull();

            then(repository).should().save(old);
            then(repository).should().save(argThat(saved ->
                    saved != old && SCHEDULE_ID.equals(saved.getSupersedesId())));

            assertThat(result.status()).isEqualTo(OncallScheduleStatus.ACTIVE.name());
            assertThat(result.supersedesId()).isEqualTo(SCHEDULE_ID);
            assertThat(result.userId()).isEqualTo("user-2");
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when the target schedule doesn't exist")
        void shouldThrowWhenNotFound() {
            // given
            final UpdateOncallScheduleRequest request =
                    buildUpdateRequest(OncallRole.PRIMARY.name());
            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    service.supersede(SCHEDULE_ID, TENANT_ID, request, ADMIN_PRINCIPAL))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(SCHEDULE_ID.toString());

            then(repository).should(never()).save(any());
        }

        /**
         * The actual regression test for the "already SUPERSEDED" guard
         * in OncallScheduleService.supersede — rejects a second attempt
         * to edit the same row (e.g. a stale client, or two concurrent
         * edits) with a clean 409 rather than silently creating a second,
         * competing replacement for the same original.
         */
        @Test
        @DisplayName("should throw when the target schedule is already SUPERSEDED")
        void shouldThrowWhenAlreadySuperseded() {
            // given
            final OncallSchedule alreadySuperseded = buildSchedule(OncallRole.PRIMARY);
            alreadySuperseded.markSuperseded();
            final UpdateOncallScheduleRequest request =
                    buildUpdateRequest(OncallRole.PRIMARY.name());

            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.of(alreadySuperseded));

            // when / then
            assertThatThrownBy(() ->
                    service.supersede(SCHEDULE_ID, TENANT_ID, request, ADMIN_PRINCIPAL))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not currently ACTIVE");

            then(repository).should(never()).save(any());
        }

        @Test
        @DisplayName("should throw BusinessException when the replacement window overlaps another schedule")
        void shouldThrowWhenOverlapping() {
            // given
            final OncallSchedule old = buildSchedule(OncallRole.PRIMARY);
            final UpdateOncallScheduleRequest request =
                    buildUpdateRequest(OncallRole.PRIMARY.name());

            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.of(old));
            given(repository.existsOverlapping(
                    anyString(), any(), any(), any(), any(), any()))
                    .willReturn(true);

            // when / then
            assertThatThrownBy(() ->
                    service.supersede(SCHEDULE_ID, TENANT_ID, request, ADMIN_PRINCIPAL))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("overlaps");

            then(repository).should(never()).save(any());
        }

        /**
         * Verifies the old row's own id is passed as excludeId — the
         * mechanism that lets existsOverlapping (backlog #43, previously
         * dead code) correctly ignore the old row against itself, since
         * it's still ACTIVE at the exact moment this check runs.
         */
        @Test
        @DisplayName("should exclude the old row's own id from the overlap check")
        void shouldExcludeOldRowIdFromOverlapCheck() {
            // given
            final OncallSchedule old = buildSchedule(OncallRole.PRIMARY);
            final UpdateOncallScheduleRequest request =
                    buildUpdateRequest(OncallRole.PRIMARY.name());

            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.of(old));
            given(repository.existsOverlapping(
                    anyString(), any(), any(), any(), any(), any()))
                    .willReturn(false);
            given(repository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            service.supersede(SCHEDULE_ID, TENANT_ID, request, ADMIN_PRINCIPAL);

            // then
            then(repository).should().existsOverlapping(
                    eq(TENANT_ID), isNull(), eq(OncallRole.PRIMARY),
                    any(), any(), eq(SCHEDULE_ID));
        }

        @Test
        @DisplayName("translates a DB-level exclusion-constraint violation into the same 409")
        void translatesDataIntegrityViolationIntoBusinessException() {
            // given
            final OncallSchedule old = buildSchedule(OncallRole.PRIMARY);
            final UpdateOncallScheduleRequest request =
                    buildUpdateRequest(OncallRole.PRIMARY.name());

            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.of(old));
            given(repository.existsOverlapping(
                    anyString(), any(), any(), any(), any(), any()))
                    .willReturn(false);
            given(repository.save(old)).willReturn(old);
            given(repository.save(argThat(s -> s != null && s != old)))
                    .willThrow(new DataIntegrityViolationException(
                            "ERROR: conflicting key value violates exclusion " +
                                    "constraint excl_oncall_schedule_overlap"));

            // when / then
            assertThatThrownBy(() ->
                    service.supersede(SCHEDULE_ID, TENANT_ID, request, ADMIN_PRINCIPAL))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("overlaps");
        }

        @Test
        @DisplayName("should throw for an invalid role string")
        void shouldThrowForInvalidRole() {
            // given
            final OncallSchedule old = buildSchedule(OncallRole.PRIMARY);
            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.of(old));

            final UpdateOncallScheduleRequest request = new UpdateOncallScheduleRequest(
                    null, "user-2", "Anna Nowak", "anna@example.com",
                    "+48100200301", "U9876543210", "NOT_A_REAL_ROLE",
                    STARTS_AT, ENDS_AT, "Replacement"
            );

            // when / then
            assertThatThrownBy(() ->
                    service.supersede(SCHEDULE_ID, TENANT_ID, request, ADMIN_PRINCIPAL))
                    .isInstanceOf(BusinessException.class);

            then(repository).should(never()).save(any());
        }

        @Test
        @DisplayName("should reject a Manager of a different team than the schedule's own")
        void shouldRejectManagerOfDifferentTeam() {
            // given
            final UUID scheduleTeam = UUID.randomUUID();
            final UUID otherTeam = UUID.randomUUID();
            final OncallSchedule old = OncallSchedule.create(
                    TENANT_ID, scheduleTeam, "user-1", "Jan Kowalski",
                    "jan@example.com", "+48100200300", "U0123456789",
                    OncallRole.PRIMARY, STARTS_AT, ENDS_AT, "Test schedule");
            final UserPrincipal managerOfOtherTeam = new UserPrincipal(
                    UUID.randomUUID(), TENANT_ID, "manager@example.com",
                    List.of("ROLE_RESPONDER"), List.of(otherTeam));

            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.of(old));

            final UpdateOncallScheduleRequest request =
                    buildUpdateRequest(OncallRole.PRIMARY.name());

            // when / then
            assertThatThrownBy(() -> service.supersede(
                    SCHEDULE_ID, TENANT_ID, request, managerOfOtherTeam))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Requires ROLE_ADMIN");

            then(repository).should(never()).save(any());
        }
    }

    /**
     * Coverage for {@code requireAdminOrTeamManager} — new authorization
     * logic added for the Manager role feature. Exercised via both
     * {@code create} and {@code delete}, since both call the same private
     * helper.
     */
    @Nested
    @DisplayName("TeamRole.MANAGER authorization")
    class TeamManagerAuthorization {

        private final UUID teamId = UUID.randomUUID();

        private UserPrincipal managerOf(UUID... teamIds) {
            return new UserPrincipal(
                    UUID.randomUUID(), TENANT_ID, "manager@example.com",
                    List.of("ROLE_RESPONDER"), List.of(teamIds),
                    List.of(teamIds));
        }

        private UserPrincipal responderNotManagerOf() {
            return new UserPrincipal(
                    UUID.randomUUID(), TENANT_ID, "responder@example.com",
                    List.of("ROLE_RESPONDER"), List.of());
        }

        @Test
        @DisplayName("create: allows a Manager of the schedule's team")
        void createAllowsManagerOfTheTeam() {
            final CreateOncallScheduleRequest request = new CreateOncallScheduleRequest(
                    teamId, "user-1", "Jan Kowalski", "jan@example.com",
                    "+48100200300", "U0123456789", OncallRole.PRIMARY.name(),
                    STARTS_AT, ENDS_AT, "Test schedule");
            given(repository.existsOverlappingForCreate(
                    anyString(), any(), any(), any(), any())).willReturn(false);
            given(repository.save(any())).willAnswer(i -> i.getArgument(0));

            assertThat(
                    service.create(TENANT_ID, request, managerOf(teamId)))
                    .isNotNull();
        }

        @Test
        @DisplayName("create: rejects a Manager of a DIFFERENT team")
        void createRejectsManagerOfDifferentTeam() {
            final UUID otherTeamId = UUID.randomUUID();
            final CreateOncallScheduleRequest request = new CreateOncallScheduleRequest(
                    teamId, "user-1", "Jan Kowalski", "jan@example.com",
                    "+48100200300", "U0123456789", OncallRole.PRIMARY.name(),
                    STARTS_AT, ENDS_AT, "Test schedule");

            assertThatThrownBy(() ->
                    service.create(TENANT_ID, request, managerOf(otherTeamId)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ROLE_ADMIN");

            then(repository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("create: rejects a plain Responder who is not a Manager of any team")
        void createRejectsPlainResponder() {
            final CreateOncallScheduleRequest request = new CreateOncallScheduleRequest(
                    teamId, "user-1", "Jan Kowalski", "jan@example.com",
                    "+48100200300", "U0123456789", OncallRole.PRIMARY.name(),
                    STARTS_AT, ENDS_AT, "Test schedule");

            assertThatThrownBy(() ->
                    service.create(TENANT_ID, request, responderNotManagerOf()))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("create: rejects a team Manager for a tenant-wide (null teamId) schedule — " +
                "a Manager's authority doesn't extend beyond their own team")
        void createRejectsManagerForTenantWideSchedule() {
            final CreateOncallScheduleRequest request = new CreateOncallScheduleRequest(
                    null, "user-1", "Jan Kowalski", "jan@example.com",
                    "+48100200300", "U0123456789", OncallRole.PRIMARY.name(),
                    STARTS_AT, ENDS_AT, "Test schedule");

            assertThatThrownBy(() ->
                    service.create(TENANT_ID, request, managerOf(teamId)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("create: allows ROLE_ADMIN regardless of team membership")
        void createAllowsAdminRegardlessOfTeam() {
            final CreateOncallScheduleRequest request = new CreateOncallScheduleRequest(
                    teamId, "user-1", "Jan Kowalski", "jan@example.com",
                    "+48100200300", "U0123456789", OncallRole.PRIMARY.name(),
                    STARTS_AT, ENDS_AT, "Test schedule");
            given(repository.existsOverlappingForCreate(
                    anyString(), any(), any(), any(), any())).willReturn(false);
            given(repository.save(any())).willAnswer(i -> i.getArgument(0));

            assertThat(
                    service.create(TENANT_ID, request, ADMIN_PRINCIPAL))
                    .isNotNull();
        }

        @Test
        @DisplayName("delete: allows a Manager of the SCHEDULE'S team (looked up from the entity, not the request)")
        void deleteAllowsManagerOfTheScheduleTeam() {
            final OncallSchedule schedule = OncallSchedule.create(
                    TENANT_ID, teamId, "user-1", "Jan Kowalski", "jan@example.com",
                    "+48100200300", "U0123456789", OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT, "Test schedule");
            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.of(schedule));

            service.delete(SCHEDULE_ID, TENANT_ID, managerOf(teamId));

            then(repository).should().delete(schedule);
        }

        @Test
        @DisplayName("delete: rejects a Manager of a different team than the schedule's")
        void deleteRejectsManagerOfDifferentTeam() {
            final UUID otherTeamId = UUID.randomUUID();
            final OncallSchedule schedule = OncallSchedule.create(
                    TENANT_ID, teamId, "user-1", "Jan Kowalski", "jan@example.com",
                    "+48100200300", "U0123456789", OncallRole.PRIMARY,
                    STARTS_AT, ENDS_AT, "Test schedule");
            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.of(schedule));

            assertThatThrownBy(() ->
                    service.delete(SCHEDULE_ID, TENANT_ID, managerOf(otherTeamId)))
                    .isInstanceOf(BusinessException.class);

            then(repository).should(never()).delete(any());
        }
    }
    @Nested
    @DisplayName("getCurrentOncall")
    class GetCurrentOncall {

        @Test
        @DisplayName("should return current PRIMARY oncall")
        void shouldReturnCurrentPrimaryOncall() {
            // given
            final OncallSchedule schedule = buildSchedule(OncallRole.PRIMARY);
            given(repository.findCurrentOncallByRole(
                    eq(TENANT_ID), eq(OncallRole.PRIMARY), any()))
                    .willReturn(Optional.of(schedule));

            // when
            final Optional<CurrentOncallResponse> result =
                    service.getCurrentOncall(TENANT_ID, OncallRole.PRIMARY.name());

            // then
            assertThat(result).isPresent();
            assertThat(result.get().role()).isEqualTo(OncallRole.PRIMARY.name());
            assertThat(result.get().email()).isEqualTo("jan@example.com");
            assertThat(result.get().slackUserId()).isEqualTo("U0123456789");
        }

        @Test
        @DisplayName("should return empty when no oncall configured")
        void shouldReturnEmptyWhenNoOncall() {
            // given
            given(repository.findCurrentOncallByRole(
                    anyString(), any(), any()))
                    .willReturn(Optional.empty());

            // when
            final Optional<CurrentOncallResponse> result =
                    service.getCurrentOncall(TENANT_ID, OncallRole.PRIMARY.name());

            // then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAllCurrentOncall")
    class GetAllCurrentOncall {

        @Test
        @DisplayName("should return all current oncall members")
        void shouldReturnAllCurrentOncall() {
            // given
            given(repository.findAllCurrentOncall(eq(TENANT_ID), any()))
                    .willReturn(List.of(
                            buildSchedule(OncallRole.PRIMARY),
                            buildSchedule(OncallRole.SECONDARY)
                    ));

            // when
            final List<CurrentOncallResponse> result =
                    service.getAllCurrentOncall(TENANT_ID);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.stream().map(CurrentOncallResponse::role))
                    .containsExactlyInAnyOrder(
                            OncallRole.PRIMARY.name(),
                            OncallRole.SECONDARY.name());
        }

        @Test
        @DisplayName("should return empty list when no oncall configured")
        void shouldReturnEmptyList() {
            // given
            given(repository.findAllCurrentOncall(anyString(), any()))
                    .willReturn(List.of());

            // when
            final List<CurrentOncallResponse> result =
                    service.getAllCurrentOncall(TENANT_ID);

            // then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("should return schedule by id")
        void shouldReturnScheduleById() {
            // given
            final OncallSchedule schedule = buildSchedule(OncallRole.PRIMARY);
            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.of(schedule));

            // when
            final OncallScheduleDto result =
                    service.getById(SCHEDULE_ID, TENANT_ID);

            // then
            assertThat(result.tenantId()).isEqualTo(TENANT_ID);
            assertThat(result.role()).isEqualTo(OncallRole.PRIMARY.name());
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            // given
            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    service.getById(SCHEDULE_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(SCHEDULE_ID.toString());
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("should delete schedule")
        void shouldDeleteSchedule() {
            // given
            final OncallSchedule schedule = buildSchedule(OncallRole.PRIMARY);
            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.of(schedule));

            // when
            service.delete(SCHEDULE_ID, TENANT_ID, ADMIN_PRINCIPAL);

            // then
            then(repository).should().delete(schedule);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when schedule not found")
        void shouldThrowWhenNotFound() {
            // given
            given(repository.findByIdAndTenantId(SCHEDULE_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    service.delete(SCHEDULE_ID, TENANT_ID, ADMIN_PRINCIPAL))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(SCHEDULE_ID.toString());

            then(repository).should(never()).delete(any());
        }
    }

    private CreateOncallScheduleRequest buildRequest(String role) {
        return new CreateOncallScheduleRequest(
                null,
                "user-1",
                "Jan Kowalski",
                "jan@example.com",
                "+48100200300",
                "U0123456789",
                role,
                STARTS_AT,
                ENDS_AT,
                "Test schedule"
        );
    }

    private UpdateOncallScheduleRequest buildUpdateRequest(String role) {
        return new UpdateOncallScheduleRequest(
                null,
                "user-2",
                "Anna Nowak",
                "anna@example.com",
                "+48100200301",
                "U9876543210",
                role,
                STARTS_AT,
                ENDS_AT,
                "Replacement"
        );
    }

    private OncallSchedule buildSchedule(OncallRole role) {
        return OncallSchedule.create(
                TENANT_ID,
                null,
                "user-1",
                "Jan Kowalski",
                "jan@example.com",
                "+48100200300",
                "U0123456789",
                role,
                STARTS_AT,
                ENDS_AT,
                "Test schedule"
        );
    }

    @Nested
    @DisplayName("findBySlackUserId")
    class FindBySlackUserId {

        @Test
        @DisplayName("should return user info when slackUserId matches within the tenant")
        void shouldReturnUserWhenFound() {
            // given
            final OncallSchedule schedule = buildSchedule(OncallRole.PRIMARY);
            given(repository.findByTenantIdAndSlackUserId(TENANT_ID, "U0123456789"))
                    .willReturn(List.of(schedule));

            // when
            final Optional<SlackUserLookupResponse> result =
                    service.findBySlackUserId(TENANT_ID, "U0123456789");

            // then
            assertThat(result).isPresent();
            assertThat(result.get().userId()).isEqualTo("user-1");
            assertThat(result.get().userName()).isEqualTo("Jan Kowalski");
            assertThat(result.get().tenantId()).isEqualTo(TENANT_ID);
            assertThat(result.get().slackUserId()).isEqualTo("U0123456789");
        }

        @Test
        @DisplayName("should return empty when no schedule found for this tenant and slackUserId")
        void shouldReturnEmptyWhenNotFound() {
            // given
            given(repository.findByTenantIdAndSlackUserId(TENANT_ID, "U_UNKNOWN"))
                    .willReturn(List.of());

            // when
            final Optional<SlackUserLookupResponse> result =
                    service.findBySlackUserId(TENANT_ID, "U_UNKNOWN");

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should only query within the given tenant — not cross-tenant")
        void shouldQueryWithTenantId() {
            // given — repository returns nothing for tenant-b
            given(repository.findByTenantIdAndSlackUserId("tenant-b", "U0123456789"))
                    .willReturn(List.of());

            // when
            final Optional<SlackUserLookupResponse> result =
                    service.findBySlackUserId("tenant-b", "U0123456789");

            // then — empty result AND correct tenantId passed to repository
            assertThat(result).isEmpty();
            then(repository).should()
                    .findByTenantIdAndSlackUserId("tenant-b", "U0123456789");
        }
    }

    @Test
    @DisplayName("should carry teamId through to the response DTO")
    void shouldIncludeTeamIdInResponse() {
        // given — regression test for OncallScheduleDto silently
        // dropping teamId even though CreateOncallScheduleRequest
        // accepted it (see OncallScheduleDto changelog).
        final UUID teamId = UUID.randomUUID();
        final CreateOncallScheduleRequest request = new CreateOncallScheduleRequest(
                teamId, "user-1", "Jan Kowalski", "jan@example.com",
                "+48100200300", "U0123456789", OncallRole.PRIMARY.name(),
                STARTS_AT, ENDS_AT, "Test schedule"
        );

        given(repository.existsOverlappingForCreate(
                eq(TENANT_ID), eq(teamId), eq(OncallRole.PRIMARY), any(), any()))
                .willReturn(false);
        given(repository.save(any()))
                .willAnswer(i -> i.getArgument(0));

        // when
        final OncallScheduleDto result = service.create(TENANT_ID, request, ADMIN_PRINCIPAL);

        // then
        assertThat(result.teamId()).isEqualTo(teamId);
    }
}