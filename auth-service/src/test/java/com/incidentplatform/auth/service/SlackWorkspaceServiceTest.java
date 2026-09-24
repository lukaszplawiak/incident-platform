package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.SlackWorkspace;
import com.incidentplatform.auth.domain.Team;
import com.incidentplatform.auth.dto.InstallSlackWorkspaceRequest;
import com.incidentplatform.auth.dto.SlackWorkspaceDto;
import com.incidentplatform.auth.dto.SlackWorkspaceInternalDto;
import com.incidentplatform.auth.repository.SlackWorkspaceRepository;
import com.incidentplatform.auth.repository.TeamRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("SlackWorkspaceService")
class SlackWorkspaceServiceTest {

    @Mock private SlackWorkspaceRepository slackWorkspaceRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private AesEncryptionService slackEncryptionService;
    @Mock private AuditEventPublisher auditEventPublisher;

    private SlackWorkspaceService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();

    private final UserPrincipal principal =
            new UserPrincipal(USER_ID, TENANT_ID, "admin@acme.com",
                    List.of("ROLE_ADMIN"), List.of());

    @BeforeEach
    void setUp() {
        service = new SlackWorkspaceService(
                slackWorkspaceRepository, teamRepository,
                slackEncryptionService, auditEventPublisher);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private InstallSlackWorkspaceRequest buildRequest(UUID teamId) {
        return new InstallSlackWorkspaceRequest(
                "T0123456", "xoxb-raw-token", teamId, "#incidents", true);
    }

    // ── install ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("install")
    class Install {

        @Test
        @DisplayName("encrypts the token, persists, and publishes an audit event")
        void install_success() {
            given(slackWorkspaceRepository.findActiveByTenantIdAndRevokedAtIsNull(TENANT_ID))
                    .willReturn(Optional.empty());
            given(slackEncryptionService.encrypt("xoxb-raw-token"))
                    .willReturn("encrypted-blob");
            given(slackWorkspaceRepository.saveAndFlush(any(SlackWorkspace.class)))
                    .willAnswer(invocation -> {
                        // Simulate JPA id assignment on persist (GenerationType.UUID).
                        final SlackWorkspace saved = invocation.getArgument(0);
                        return SlackWorkspace.forTesting(
                                UUID.randomUUID(), saved.getTenantId(), saved.getSlackTeamId());
                    });

            final SlackWorkspaceDto dto = service.install(buildRequest(null), principal);

            // Fields come from the mocked save()'s return value here (a
            // forTesting() fixture simulating JPA id assignment), so this
            // asserts what install() itself controls (id present, active);
            // field-mapping correctness (channel/broadcast pass-through) is
            // covered by SlackWorkspaceDto.from being a direct field copy.
            assertThat(dto.id()).isNotNull();
            assertThat(dto.active()).isTrue();

            then(slackWorkspaceRepository).should().saveAndFlush(any(SlackWorkspace.class));
            then(auditEventPublisher).should().publishAuth(
                    eq(USER_ID), eq(TENANT_ID),
                    eq("SLACK_WORKSPACE_INSTALLED"), eq("auth-service"),
                    eq(USER_ID.toString()), any(), any());
        }

        @Test
        @DisplayName("never persists the raw token — only the encrypted blob")
        void install_neverPersistsRawToken() {
            given(slackWorkspaceRepository.findActiveByTenantIdAndRevokedAtIsNull(TENANT_ID))
                    .willReturn(Optional.empty());
            given(slackEncryptionService.encrypt("xoxb-raw-token"))
                    .willReturn("encrypted-blob");
            given(slackWorkspaceRepository.saveAndFlush(any(SlackWorkspace.class)))
                    .willAnswer(invocation -> SlackWorkspace.forTesting(
                            UUID.randomUUID(), TENANT_ID, "T0123456"));

            service.install(buildRequest(null), principal);

            then(slackWorkspaceRepository).should().saveAndFlush(
                    argThatEncryptedTokenIs("encrypted-blob"));
        }

        private SlackWorkspace argThatEncryptedTokenIs(String expected) {
            return org.mockito.ArgumentMatchers.argThat(
                    workspace -> expected.equals(workspace.getBotTokenEncrypted()));
        }

        @Test
        @DisplayName("rejects a second install with 409 when an active workspace already exists")
        void install_rejectsSecondActiveWorkspace() {
            given(slackWorkspaceRepository.findActiveByTenantIdAndRevokedAtIsNull(TENANT_ID))
                    .willReturn(Optional.of(SlackWorkspace.install(
                            TENANT_ID, null, "T_existing", "enc", null, false)));

            assertThatThrownBy(() -> service.install(buildRequest(null), principal))
                    .isInstanceOf(BusinessException.class);

            then(slackWorkspaceRepository).should(never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("resolves an optional teamId, 404s if the team doesn't exist in this tenant")
        void install_rejectsUnknownTeam() {
            given(slackWorkspaceRepository.findActiveByTenantIdAndRevokedAtIsNull(TENANT_ID))
                    .willReturn(Optional.empty());
            given(teamRepository.findByIdAndTenantId(TEAM_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.install(buildRequest(TEAM_ID), principal))
                    .isInstanceOf(ResourceNotFoundException.class);

            then(slackWorkspaceRepository).should(never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a concurrent install that loses the race on the unique index gets 409, and no audit event")
        void install_raceOnUniqueIndexIs409() {
            given(slackWorkspaceRepository.findActiveByTenantIdAndRevokedAtIsNull(TENANT_ID))
                    .willReturn(Optional.empty());
            given(slackEncryptionService.encrypt(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn("encrypted-blob");
            given(slackWorkspaceRepository.saveAndFlush(any(SlackWorkspace.class)))
                    .willThrow(new DataIntegrityViolationException("dup",
                            new ConstraintViolationException("dup", new SQLException(),
                                    "uq_slack_workspaces_active_tenant")));

            assertThatThrownBy(() -> service.install(buildRequest(null), principal))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT));

            then(auditEventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("any other integrity violation is not disguised as 409 — it propagates")
        void install_otherIntegrityViolationPropagates() {
            given(slackWorkspaceRepository.findActiveByTenantIdAndRevokedAtIsNull(TENANT_ID))
                    .willReturn(Optional.empty());
            given(slackEncryptionService.encrypt(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn("encrypted-blob");
            given(slackWorkspaceRepository.saveAndFlush(any(SlackWorkspace.class)))
                    .willThrow(new DataIntegrityViolationException("other",
                            new ConstraintViolationException("other", new SQLException(),
                                    "chk_slack_workspaces_slack_team_id_not_blank")));

            assertThatThrownBy(() -> service.install(buildRequest(null), principal))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ── get ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("404s when the tenant has no active workspace")
        void get_notFound() {
            given(slackWorkspaceRepository.findActiveByTenantIdAndRevokedAtIsNull(TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.get())
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("never includes the token in the admin-facing DTO")
        void get_neverExposesToken() {
            final SlackWorkspace workspace = SlackWorkspace.install(
                    TENANT_ID, null, "T0123456", "encrypted-blob", "#incidents", false);
            given(slackWorkspaceRepository.findActiveByTenantIdAndRevokedAtIsNull(TENANT_ID))
                    .willReturn(Optional.of(workspace));

            final SlackWorkspaceDto dto = service.get();

            // SlackWorkspaceDto simply has no token field — this asserts the
            // record's shape stays that way even if a field gets added later.
            assertThat(dto.getClass().getRecordComponents())
                    .noneMatch(c -> c.getName().toLowerCase().contains("token"));
        }
    }

    // ── revoke ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("revoke")
    class Revoke {

        @Test
        @DisplayName("revokes and publishes an audit event")
        void revoke_success() {
            final UUID id = UUID.randomUUID();
            final SlackWorkspace workspace = SlackWorkspace.install(
                    TENANT_ID, null, "T0123456", "encrypted-blob", null, false);
            given(slackWorkspaceRepository.findByIdAndTenantId(id, TENANT_ID))
                    .willReturn(Optional.of(workspace));

            service.revoke(id, principal);

            assertThat(workspace.isRevoked()).isTrue();
            then(slackWorkspaceRepository).should().save(workspace);
            then(auditEventPublisher).should().publishAuth(
                    eq(USER_ID), eq(TENANT_ID),
                    eq("SLACK_WORKSPACE_REVOKED"), eq("auth-service"),
                    eq(USER_ID.toString()), any(), any());
        }

        @Test
        @DisplayName("rejects revoking an already-revoked workspace with 409")
        void revoke_rejectsAlreadyRevoked() {
            final UUID id = UUID.randomUUID();
            final SlackWorkspace workspace = SlackWorkspace.install(
                    TENANT_ID, null, "T0123456", "encrypted-blob", null, false);
            workspace.revoke();
            given(slackWorkspaceRepository.findByIdAndTenantId(id, TENANT_ID))
                    .willReturn(Optional.of(workspace));

            assertThatThrownBy(() -> service.revoke(id, principal))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("404s for an unknown id")
        void revoke_notFound() {
            final UUID id = UUID.randomUUID();
            given(slackWorkspaceRepository.findByIdAndTenantId(id, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.revoke(id, principal))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── getForServiceRead ────────────────────────────────────────────────

    @Nested
    @DisplayName("getForServiceRead")
    class GetForServiceRead {

        @Test
        @DisplayName("decrypts the token for an active workspace")
        void getForServiceRead_decryptsToken() {
            final SlackWorkspace workspace = SlackWorkspace.install(
                    TENANT_ID, TEAM_ID, "T0123456", "encrypted-blob", "#incidents", true);
            given(slackWorkspaceRepository.findActiveByTenantIdAndRevokedAtIsNull(TENANT_ID))
                    .willReturn(Optional.of(workspace));
            given(slackEncryptionService.decrypt("encrypted-blob"))
                    .willReturn("xoxb-raw-token");

            final Optional<SlackWorkspaceInternalDto> result = service.getForServiceRead();

            assertThat(result).isPresent();
            assertThat(result.get().botToken()).isEqualTo("xoxb-raw-token");
            assertThat(result.get().defaultChannel()).isEqualTo("#incidents");
            assertThat(result.get().broadcastEnabled()).isTrue();
            assertThat(result.get().teamId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("returns empty (not an exception) when the tenant has no active workspace — " +
                "callers must be able to tell 'not configured' apart from 'unreachable'")
        void getForServiceRead_emptyWhenAbsent() {
            given(slackWorkspaceRepository.findActiveByTenantIdAndRevokedAtIsNull(TENANT_ID))
                    .willReturn(Optional.empty());

            assertThat(service.getForServiceRead()).isEmpty();
            then(slackEncryptionService).should(never()).decrypt(any());
        }
    }
}
