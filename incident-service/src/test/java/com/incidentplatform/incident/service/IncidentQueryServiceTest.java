package com.incidentplatform.incident.service;

import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentHistory;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.incident.dto.IncidentDto;
import com.incidentplatform.incident.dto.IncidentFilter;
import com.incidentplatform.incident.dto.IncidentHistoryDto;
import com.incidentplatform.incident.repository.IncidentHistoryRepository;
import com.incidentplatform.incident.repository.IncidentRepository;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.SourceType;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
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
import org.springframework.data.jpa.domain.Specification;

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
@DisplayName("IncidentQueryService")
class IncidentQueryServiceTest {

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private IncidentHistoryRepository historyRepository;

    private IncidentQueryService queryService;

    private static final String TENANT_ID = "test-tenant";
    private static final Pageable DEFAULT_PAGEABLE =
            PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        queryService = new IncidentQueryService(
                incidentRepository, historyRepository);
    }

    @Nested
    @DisplayName("findAll")
    class FindAll {

        @Test
        @DisplayName("should use simple query when no filters provided")
        void shouldUseSimpleQueryWhenNoFilters() {
            // given
            final IncidentFilter emptyFilter = new IncidentFilter(
                    null, null, null, null);
            final List<Incident> incidents = List.of(
                    buildIncident(Severity.CRITICAL),
                    buildIncident(Severity.HIGH)
            );
            final Page<Incident> page = new PageImpl<>(incidents);

            given(incidentRepository.findByTenantIdOrderByCreatedAtDesc(
                    TENANT_ID, DEFAULT_PAGEABLE)).willReturn(page);

            // when
            final Page<IncidentDto> result = queryService.findAll(
                    TENANT_ID, emptyFilter, DEFAULT_PAGEABLE);

            // then
            assertThat(result.getContent()).hasSize(2);
            then(incidentRepository).should()
                    .findByTenantIdOrderByCreatedAtDesc(TENANT_ID, DEFAULT_PAGEABLE);
            then(incidentRepository).should(never())
                    .findAll(any(Specification.class), any(Pageable.class));
        }

        @Test
        @DisplayName("should use Specification when status filter provided")
        void shouldUseSpecificationWhenStatusFilter() {
            // given
            final IncidentFilter filter = new IncidentFilter(
                    IncidentStatus.OPEN, null, null, null);
            final List<Incident> incidents = List.of(buildIncident(Severity.CRITICAL));
            final Page<Incident> page = new PageImpl<>(incidents);

            given(incidentRepository.findAll(
                    any(Specification.class), eq(DEFAULT_PAGEABLE)))
                    .willReturn(page);

            // when
            final Page<IncidentDto> result = queryService.findAll(
                    TENANT_ID, filter, DEFAULT_PAGEABLE);

            // then
            assertThat(result.getContent()).hasSize(1);
            then(incidentRepository).should()
                    .findAll(any(Specification.class), eq(DEFAULT_PAGEABLE));
            then(incidentRepository).should(never())
                    .findByTenantIdOrderByCreatedAtDesc(anyString(), any(Pageable.class));
        }

        @Test
        @DisplayName("should use Specification when severity filter provided")
        void shouldUseSpecificationWhenSeverityFilter() {
            // given
            // IncidentFilter przyjmuje Severity enum — brak konwersji String
            final IncidentFilter filter = new IncidentFilter(
                    null, Severity.CRITICAL, null, null);
            final Page<Incident> page = new PageImpl<>(
                    List.of(buildIncident(Severity.CRITICAL)));

            given(incidentRepository.findAll(
                    any(Specification.class), eq(DEFAULT_PAGEABLE)))
                    .willReturn(page);

            // when
            final Page<IncidentDto> result = queryService.findAll(
                    TENANT_ID, filter, DEFAULT_PAGEABLE);

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).severity())
                    .isEqualTo(Severity.CRITICAL);
        }

        @Test
        @DisplayName("should use Specification when multiple filters provided")
        void shouldUseSpecificationWhenMultipleFilters() {
            // given
            final IncidentFilter filter = new IncidentFilter(
                    IncidentStatus.OPEN, Severity.CRITICAL, SourceType.SECURITY, "wazuh");
            final Page<Incident> page = new PageImpl<>(List.of());

            given(incidentRepository.findAll(
                    any(Specification.class), eq(DEFAULT_PAGEABLE)))
                    .willReturn(page);

            // when
            final Page<IncidentDto> result = queryService.findAll(
                    TENANT_ID, filter, DEFAULT_PAGEABLE);

            // then
            assertThat(result.getContent()).isEmpty();
            then(incidentRepository).should()
                    .findAll(any(Specification.class), eq(DEFAULT_PAGEABLE));
        }

        @Test
        @DisplayName("should return empty page when no incidents found")
        void shouldReturnEmptyPageWhenNoIncidents() {
            // given
            final IncidentFilter emptyFilter = new IncidentFilter(
                    null, null, null, null);
            given(incidentRepository.findByTenantIdOrderByCreatedAtDesc(
                    TENANT_ID, DEFAULT_PAGEABLE))
                    .willReturn(new PageImpl<>(List.of()));

            // when
            final Page<IncidentDto> result = queryService.findAll(
                    TENANT_ID, emptyFilter, DEFAULT_PAGEABLE);

            // then
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("should map Incident to IncidentDto correctly")
        void shouldMapIncidentToDto() {
            // given
            final Incident incident = buildIncident(Severity.CRITICAL);
            final Page<Incident> page = new PageImpl<>(List.of(incident));
            final IncidentFilter emptyFilter = new IncidentFilter(
                    null, null, null, null);

            given(incidentRepository.findByTenantIdOrderByCreatedAtDesc(
                    TENANT_ID, DEFAULT_PAGEABLE)).willReturn(page);

            // when
            final Page<IncidentDto> result = queryService.findAll(
                    TENANT_ID, emptyFilter, DEFAULT_PAGEABLE);

            // then
            final IncidentDto dto = result.getContent().get(0);
            assertThat(dto.id()).isEqualTo(incident.getId());
            assertThat(dto.tenantId()).isEqualTo(TENANT_ID);
            assertThat(dto.status()).isEqualTo(IncidentStatus.OPEN);
            assertThat(dto.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(dto.source()).isEqualTo("prometheus");
        }

        @Test
        @DisplayName("should return pageable metadata correctly")
        void shouldReturnPageableMetadata() {
            // given
            final List<Incident> incidents = List.of(
                    buildIncident(Severity.HIGH),
                    buildIncident(Severity.MEDIUM),
                    buildIncident(Severity.LOW)
            );
            final Page<Incident> page = new PageImpl<>(
                    incidents, DEFAULT_PAGEABLE, 3L);
            final IncidentFilter emptyFilter = new IncidentFilter(
                    null, null, null, null);

            given(incidentRepository.findByTenantIdOrderByCreatedAtDesc(
                    TENANT_ID, DEFAULT_PAGEABLE)).willReturn(page);

            // when
            final Page<IncidentDto> result = queryService.findAll(
                    TENANT_ID, emptyFilter, DEFAULT_PAGEABLE);

            // then
            assertThat(result.getNumberOfElements()).isEqualTo(3);
            assertThat(result.getTotalElements()).isEqualTo(3L);
            assertThat(result.getNumber()).isZero();
        }
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("should return IncidentDto when incident found")
        void shouldReturnDtoWhenFound() {
            // given
            final Incident incident = buildIncident(Severity.CRITICAL);

            given(incidentRepository.findByIdAndTenantId(
                    incident.getId(), TENANT_ID))
                    .willReturn(Optional.of(incident));

            // when
            final IncidentDto result = queryService.findById(
                    incident.getId(), TENANT_ID);

            // then
            assertThat(result.id()).isEqualTo(incident.getId());
            assertThat(result.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(result.status()).isEqualTo(IncidentStatus.OPEN);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when incident not found")
        void shouldThrowWhenNotFound() {
            // given
            final UUID unknownId = UUID.randomUUID();
            given(incidentRepository.findByIdAndTenantId(unknownId, TENANT_ID))
                    .willReturn(Optional.empty());

            // then
            assertThatThrownBy(() -> queryService.findById(unknownId, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(unknownId.toString());
        }

        @Test
        @DisplayName("should always filter by tenantId for security")
        void shouldAlwaysFilterByTenantId() {
            // given
            final UUID incidentId = UUID.randomUUID();
            given(incidentRepository.findByIdAndTenantId(incidentId, TENANT_ID))
                    .willReturn(Optional.empty());

            // when/then
            assertThatThrownBy(() -> queryService.findById(incidentId, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            then(incidentRepository).should()
                    .findByIdAndTenantId(incidentId, TENANT_ID);
        }

        @Test
        @DisplayName("should return DTO with allowedTransitions based on current status")
        void shouldReturnDtoWithAllowedTransitions() {
            // given
            final Incident incident = buildIncident(Severity.HIGH);

            given(incidentRepository.findByIdAndTenantId(
                    incident.getId(), TENANT_ID))
                    .willReturn(Optional.of(incident));

            // when
            final IncidentDto result = queryService.findById(
                    incident.getId(), TENANT_ID);

            // then
            assertThat(result.allowedTransitions())
                    .containsExactlyInAnyOrder(
                            IncidentStatus.ACKNOWLEDGED,
                            IncidentStatus.ESCALATED);
        }
    }

    @Nested
    @DisplayName("findHistory")
    class FindHistory {

        @Test
        @DisplayName("should return history entries for existing incident")
        void shouldReturnHistoryForExistingIncident() {
            // given
            final UUID incidentId = UUID.randomUUID();
            final List<IncidentHistory> historyEntries = List.of(
                    buildHistory(incidentId, null, IncidentStatus.OPEN,
                            "KAFKA_CONSUMER"),
                    buildHistory(incidentId, IncidentStatus.OPEN,
                            IncidentStatus.ACKNOWLEDGED, "REST_API")
            );

            given(incidentRepository.existsById(incidentId)).willReturn(true);
            given(historyRepository.findByIncidentIdAndTenantIdOrderByChangedAtAsc(
                    incidentId, TENANT_ID)).willReturn(historyEntries);

            // when
            final List<IncidentHistoryDto> result = queryService.findHistory(
                    incidentId, TENANT_ID);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).toStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(result.get(0).fromStatus()).isNull();
            assertThat(result.get(0).changeSource()).isEqualTo("KAFKA_CONSUMER");
            assertThat(result.get(1).toStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
            assertThat(result.get(1).fromStatus()).isEqualTo(IncidentStatus.OPEN);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when incident not found")
        void shouldThrowWhenIncidentNotFound() {
            // given
            final UUID unknownId = UUID.randomUUID();
            given(incidentRepository.existsById(unknownId)).willReturn(false);

            // then
            assertThatThrownBy(() -> queryService.findHistory(unknownId, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(unknownId.toString());
        }

        @Test
        @DisplayName("should return empty list when no history entries exist")
        void shouldReturnEmptyListWhenNoHistory() {
            // given
            final UUID incidentId = UUID.randomUUID();
            given(incidentRepository.existsById(incidentId)).willReturn(true);
            given(historyRepository.findByIncidentIdAndTenantIdOrderByChangedAtAsc(
                    incidentId, TENANT_ID)).willReturn(List.of());

            // when
            final List<IncidentHistoryDto> result = queryService.findHistory(
                    incidentId, TENANT_ID);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return history sorted chronologically")
        void shouldReturnHistorySortedChronologically() {
            // given
            final UUID incidentId = UUID.randomUUID();
            final List<IncidentHistory> historyEntries = List.of(
                    buildHistory(incidentId, null, IncidentStatus.OPEN, "KAFKA_CONSUMER"),
                    buildHistory(incidentId, IncidentStatus.OPEN,
                            IncidentStatus.ACKNOWLEDGED, "REST_API"),
                    buildHistory(incidentId, IncidentStatus.ACKNOWLEDGED,
                            IncidentStatus.RESOLVED, "REST_API")
            );

            given(incidentRepository.existsById(incidentId)).willReturn(true);
            given(historyRepository.findByIncidentIdAndTenantIdOrderByChangedAtAsc(
                    incidentId, TENANT_ID)).willReturn(historyEntries);

            // when
            final List<IncidentHistoryDto> result = queryService.findHistory(
                    incidentId, TENANT_ID);

            // then
            assertThat(result).hasSize(3);
            assertThat(result.get(0).toStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(result.get(1).toStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
            assertThat(result.get(2).toStatus()).isEqualTo(IncidentStatus.RESOLVED);
        }
    }

    private Incident buildIncident(Severity severity) {
        return new Incident(
                TENANT_ID,
                "High CPU usage on prod-server-1",
                "CPU exceeded 95%",
                severity,
                SourceType.OPS,
                "prometheus",
                "prometheus:highcpuusage:server-1",
                UUID.randomUUID(),
                Instant.now().minusSeconds(60)
        );
    }

    private IncidentHistory buildHistory(UUID incidentId,
                                         IncidentStatus fromStatus,
                                         IncidentStatus toStatus,
                                         String changeSource) {
        return new IncidentHistory(
                incidentId, TENANT_ID,
                fromStatus, toStatus,
                null, changeSource, null
        );
    }
}