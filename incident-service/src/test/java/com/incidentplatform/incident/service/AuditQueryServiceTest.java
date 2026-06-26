package com.incidentplatform.incident.service;

import com.incidentplatform.incident.domain.AuditEvent;
import com.incidentplatform.incident.dto.AuditEventDto;
import com.incidentplatform.incident.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditQueryService")
class AuditQueryServiceTest {

    @Mock
    private AuditEventRepository repository;

    private AuditQueryService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final Pageable DEFAULT_PAGEABLE = PageRequest.of(0, 50);

    @BeforeEach
    void setUp() {
        service = new AuditQueryService(repository);
    }

    // ── getAuditLog(UUID, String) — List variant ──────────────────────────

    @Nested
    @DisplayName("getAuditLog — list")
    class GetAuditLogList {

        @Test
        @DisplayName("returns mapped list of AuditEventDto in order")
        void returnsMappedList() {
            // given
            final AuditEvent e1 = buildSystemEvent("INCIDENT_CREATED");
            final AuditEvent e2 = buildSystemEvent("INCIDENT_ESCALATED");
            given(repository.findByTenantIdAndIncidentIdOrderByOccurredAtAsc(
                    TENANT_ID, INCIDENT_ID))
                    .willReturn(List.of(e1, e2));

            // when
            final List<AuditEventDto> result =
                    service.getAuditLog(INCIDENT_ID, TENANT_ID);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).eventType()).isEqualTo("INCIDENT_CREATED");
            assertThat(result.get(1).eventType()).isEqualTo("INCIDENT_ESCALATED");
        }

        @Test
        @DisplayName("returns empty list when no events exist")
        void returnsEmptyList() {
            // given
            given(repository.findByTenantIdAndIncidentIdOrderByOccurredAtAsc(
                    any(), any()))
                    .willReturn(List.of());

            // when
            final List<AuditEventDto> result =
                    service.getAuditLog(INCIDENT_ID, TENANT_ID);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("delegates to repository with correct tenant and incident ID")
        void delegatesToRepositoryWithCorrectArgs() {
            // given
            given(repository.findByTenantIdAndIncidentIdOrderByOccurredAtAsc(
                    any(), any()))
                    .willReturn(List.of());

            // when
            service.getAuditLog(INCIDENT_ID, TENANT_ID);

            // then
            then(repository).should()
                    .findByTenantIdAndIncidentIdOrderByOccurredAtAsc(
                            TENANT_ID, INCIDENT_ID);
        }
    }

    // ── getAuditLog(UUID, String, Pageable) — Page variant ────────────────

    @Nested
    @DisplayName("getAuditLog — paginated")
    class GetAuditLogPaginated {

        @Test
        @DisplayName("returns mapped page of AuditEventDto")
        void returnsMappedPage() {
            // given
            final AuditEvent event = buildSystemEvent("INCIDENT_CREATED");
            final Page<AuditEvent> repoPage =
                    new PageImpl<>(List.of(event), DEFAULT_PAGEABLE, 1L);

            given(repository.findByTenantIdAndIncidentIdOrderByOccurredAtAsc(
                    eq(TENANT_ID), eq(INCIDENT_ID), eq(DEFAULT_PAGEABLE)))
                    .willReturn(repoPage);

            // when
            final Page<AuditEventDto> result =
                    service.getAuditLog(INCIDENT_ID, TENANT_ID, DEFAULT_PAGEABLE);

            // then
            assertThat(result.getTotalElements()).isEqualTo(1L);
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).incidentId()).isEqualTo(INCIDENT_ID);
            assertThat(result.getContent().get(0).eventType()).isEqualTo("INCIDENT_CREATED");
        }

        @Test
        @DisplayName("returns empty page when no events exist")
        void returnsEmptyPage() {
            // given
            given(repository.findByTenantIdAndIncidentIdOrderByOccurredAtAsc(
                    any(), any(), eq(DEFAULT_PAGEABLE)))
                    .willReturn(Page.empty(DEFAULT_PAGEABLE));

            // when
            final Page<AuditEventDto> result =
                    service.getAuditLog(INCIDENT_ID, TENANT_ID, DEFAULT_PAGEABLE);

            // then
            assertThat(result.getTotalElements()).isZero();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.isFirst()).isTrue();
            assertThat(result.isLast()).isTrue();
        }

        @Test
        @DisplayName("page metadata is propagated correctly")
        void pageMetadataPropagated() {
            // given
            final Pageable page2 = PageRequest.of(1, 10);
            final List<AuditEvent> events = List.of(
                    buildSystemEvent("INCIDENT_ESCALATED"),
                    buildSystemEvent("INCIDENT_RESOLVED")
            );
            final Page<AuditEvent> repoPage = new PageImpl<>(events, page2, 12L);

            given(repository.findByTenantIdAndIncidentIdOrderByOccurredAtAsc(
                    any(), any(), eq(page2)))
                    .willReturn(repoPage);

            // when
            final Page<AuditEventDto> result =
                    service.getAuditLog(INCIDENT_ID, TENANT_ID, page2);

            // then
            assertThat(result.getNumber()).isEqualTo(1);
            assertThat(result.getSize()).isEqualTo(10);
            assertThat(result.getTotalElements()).isEqualTo(12L);
            assertThat(result.getTotalPages()).isEqualTo(2);
            assertThat(result.isFirst()).isFalse();
            assertThat(result.isLast()).isTrue();
        }

        @Test
        @DisplayName("delegates to repository with correct tenant, incident ID and pageable")
        void delegatesToRepositoryWithCorrectArgs() {
            // given
            given(repository.findByTenantIdAndIncidentIdOrderByOccurredAtAsc(
                    any(), any(), any()))
                    .willReturn(Page.empty());

            // when
            service.getAuditLog(INCIDENT_ID, TENANT_ID, DEFAULT_PAGEABLE);

            // then
            then(repository).should()
                    .findByTenantIdAndIncidentIdOrderByOccurredAtAsc(
                            TENANT_ID, INCIDENT_ID, DEFAULT_PAGEABLE);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private AuditEvent buildSystemEvent(String eventType) {
        return AuditEvent.system(
                INCIDENT_ID, TENANT_ID,
                eventType, "incident-service",
                "Test event", null);
    }
}