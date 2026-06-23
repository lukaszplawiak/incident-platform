package com.incidentplatform.oncall.service;

import com.incidentplatform.oncall.domain.OncallRole;
import com.incidentplatform.oncall.domain.OncallSchedule;
import com.incidentplatform.oncall.dto.CreateOncallScheduleRequest;
import com.incidentplatform.oncall.dto.CurrentOncallResponse;
import com.incidentplatform.oncall.dto.OncallScheduleDto;
import com.incidentplatform.oncall.dto.SlackUserLookupResponse;
import com.incidentplatform.oncall.repository.OncallScheduleRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
                    eq(TENANT_ID), eq(OncallRole.PRIMARY.name()), any(), any()))
                    .willReturn(false);
            given(repository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            // when
            final OncallScheduleDto result =
                    service.create(TENANT_ID, request);

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
                    anyString(), anyString(), any(), any()))
                    .willReturn(false);
            given(repository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            // when
            service.create(TENANT_ID, request);

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
                    anyString(), anyString(), any(), any()))
                    .willReturn(true);

            // when / then
            // BusinessException → GlobalExceptionHandler → 409 Conflict.
            assertThatThrownBy(() -> service.create(TENANT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("overlaps");

            then(repository).should(never()).save(any());
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
                    eq(TENANT_ID), eq(OncallRole.PRIMARY.name()), any()))
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
                    anyString(), anyString(), any()))
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
            service.delete(SCHEDULE_ID, TENANT_ID);

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
                    service.delete(SCHEDULE_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(SCHEDULE_ID.toString());

            then(repository).should(never()).delete(any());
        }
    }

    private CreateOncallScheduleRequest buildRequest(String role) {
        return new CreateOncallScheduleRequest(
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

    private OncallSchedule buildSchedule(OncallRole role) {
        return OncallSchedule.create(
                TENANT_ID,
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
}