package com.incidentplatform.postmortem.service;

import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.dto.PostmortemDto;
import com.incidentplatform.postmortem.dto.UpdatePostmortemRequest;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.domain.Severity;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("PostmortemService")
class PostmortemServiceTest {

    @Mock
    private PostmortemRepository postmortemRepository;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private PostmortemService postmortemService;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String TENANT_ID = "test-tenant";
    private static final String TITLE = "High CPU Usage on prod-server-1";
    private static final Instant OPENED_AT = Instant.now().minusSeconds(30 * 60L);
    private static final Instant RESOLVED_AT = Instant.now();
    private static final int DURATION = 30;

    @BeforeEach
    void setUp() {
        postmortemService = new PostmortemService(
                postmortemRepository, auditEventPublisher);
    }

    @Nested
    @DisplayName("getPostmortems")
    class GetPostmortems {

        @Test
        @DisplayName("should return paginated postmortems for tenant")
        void shouldReturnPostmortemsForTenant() {
            final Postmortem postmortem = buildDraftPostmortem();
            final Pageable pageable = PageRequest.of(0, 20);
            given(postmortemRepository
                    .findByTenantIdOrderByCreatedAtDesc(TENANT_ID, pageable))
                    .willReturn(new PageImpl<>(List.of(postmortem), pageable, 1));

            final Page<PostmortemDto> result =
                    postmortemService.getPostmortems(TENANT_ID, pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).incidentId()).isEqualTo(INCIDENT_ID);
            assertThat(result.getContent().get(0).status()).isEqualTo("DRAFT");
            assertThat(result.getTotalElements()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("getByIncidentId")
    class GetByIncidentId {

        @Test
        @DisplayName("should return postmortem for incident")
        void shouldReturnPostmortemForIncident() {
            final Postmortem postmortem = buildDraftPostmortem();
            given(postmortemRepository
                    .findByIncidentIdAndTenantId(INCIDENT_ID, TENANT_ID))
                    .willReturn(Optional.of(postmortem));

            final PostmortemDto result =
                    postmortemService.getByIncidentId(INCIDENT_ID, TENANT_ID);

            assertThat(result.incidentId()).isEqualTo(INCIDENT_ID);
            assertThat(result.tenantId()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            given(postmortemRepository
                    .findByIncidentIdAndTenantId(INCIDENT_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    postmortemService.getByIncidentId(INCIDENT_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(INCIDENT_ID.toString());
        }
    }

    @Nested
    @DisplayName("updateContent")
    class UpdateContent {

        @Test
        @DisplayName("should update postmortem content")
        void shouldUpdateContent() {
            final Postmortem postmortem = buildDraftPostmortem();
            given(postmortemRepository
                    .findByIncidentIdAndTenantId(INCIDENT_ID, TENANT_ID))
                    .willReturn(Optional.of(postmortem));
            given(postmortemRepository.save(any()))
                    .willAnswer(i -> i.getArgument(0));

            final PostmortemDto result = postmortemService.updateContent(
                    INCIDENT_ID, TENANT_ID,
                    new UpdatePostmortemRequest("Updated content by engineer"));

            assertThat(result.content()).isEqualTo("Updated content by engineer");
            then(postmortemRepository).should().save(postmortem);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when postmortem not found")
        void shouldThrowWhenNotFound() {
            given(postmortemRepository
                    .findByIncidentIdAndTenantId(INCIDENT_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    postmortemService.updateContent(
                            INCIDENT_ID, TENANT_ID,
                            new UpdatePostmortemRequest("content")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(INCIDENT_ID.toString());
        }
    }

    private Postmortem buildDraftPostmortem() {
        final Postmortem postmortem = Postmortem.createGenerating(
                INCIDENT_ID, TENANT_ID, TITLE, Severity.CRITICAL,
                OPENED_AT, RESOLVED_AT, DURATION);
        postmortem.markDraft("## Summary\nTest postmortem content", "test prompt");
        return postmortem;
    }
}