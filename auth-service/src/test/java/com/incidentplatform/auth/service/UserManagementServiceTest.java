package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.UpdateUserRolesRequest;
import com.incidentplatform.auth.dto.UpdateUserStatusRequest;
import com.incidentplatform.auth.dto.UserSummaryDto;
import com.incidentplatform.auth.repository.TeamMemberRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserManagementService")
class UserManagementServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private ApiKeyService apiKeyService;

    private UserManagementService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID USER_ID     = UUID.randomUUID();
    private static final UUID ADMIN_ID    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserManagementService(
                userRepository, teamMemberRepository, auditEventPublisher, apiKeyService);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── updateRoles ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateRoles")
    class UpdateRoles {

        @Test
        @DisplayName("replaces existing roles with new set")
        void replacesRoles() {
            final User user = buildUser("ROLE_RESPONDER");
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            final UserSummaryDto result = service.updateRoles(
                    USER_ID, new UpdateUserRolesRequest(List.of("ROLE_ADMIN")));

            assertThat(result.roles()).containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("throws 404 when user not found")
        void throwsNotFound() {
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateRoles(
                    USER_ID, new UpdateUserRolesRequest(List.of("ROLE_ADMIN"))))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── updateStatus ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatus {

        @Test
        @DisplayName("deactivates active user")
        void deactivatesUser() {
            final User user = buildUser("ROLE_RESPONDER");
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            final UserSummaryDto result = service.updateStatus(
                    USER_ID, new UpdateUserStatusRequest(false));

            assertThat(result.active()).isFalse();
        }

        @Test
        @DisplayName("throws 404 when user not found")
        void throwsNotFound() {
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateStatus(
                    USER_ID, new UpdateUserStatusRequest(false)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── archiveUser ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("archiveUser")
    class ArchiveUser {

        @Test
        @DisplayName("sets archived_at and deactivates user")
        void archivesUser() {
            final User user = buildUser("ROLE_RESPONDER");
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.archiveUser(USER_ID, buildPrincipal(ADMIN_ID));

            final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            then(userRepository).should().save(captor.capture());
            assertThat(captor.getValue().isArchived()).isTrue();
            assertThat(captor.getValue().getArchivedAt()).isNotNull();
            assertThat(captor.getValue().isActive()).isFalse();
        }

        @Test
        @DisplayName("throws 403 when admin tries to archive themselves")
        void throws403OnSelfArchive() {
            assertThatThrownBy(() ->
                    service.archiveUser(ADMIN_ID, buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("throws 404 when user not found")
        void throws404WhenNotFound() {
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.archiveUser(USER_ID, buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── restoreUser ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("restoreUser")
    class RestoreUser {

        @Test
        @DisplayName("restores archived user — clears archived_at and activates")
        void restoresArchivedUser() {
            final User user = buildUser("ROLE_RESPONDER");
            user.archive();
            given(userRepository.findAnyByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.restoreUser(USER_ID, buildPrincipal(ADMIN_ID));

            final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            then(userRepository).should().save(captor.capture());
            assertThat(captor.getValue().isArchived()).isFalse();
            assertThat(captor.getValue().getArchivedAt()).isNull();
            assertThat(captor.getValue().isActive()).isTrue();
        }

        @Test
        @DisplayName("throws 409 when user is anonymized — irreversible")
        void throws409WhenAnonymized() {
            final User user = buildUser("ROLE_RESPONDER");
            user.archive();
            user.anonymize(UUID.randomUUID());
            given(userRepository.findAnyByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    service.restoreUser(USER_ID, buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("throws 409 when user is not archived")
        void throws409WhenNotArchived() {
            final User user = buildUser("ROLE_RESPONDER"); // active
            given(userRepository.findAnyByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    service.restoreUser(USER_ID, buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("throws 404 when user not found")
        void throws404WhenNotFound() {
            given(userRepository.findAnyByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.restoreUser(USER_ID, buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── anonymizeUser ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("anonymizeUser")
    class AnonymizeUser {

        @Test
        @DisplayName("replaces email with anonymous alias and nulls password")
        void anonymizesPersonalData() {
            final User user = buildUser("ROLE_RESPONDER");
            user.archive();
            given(userRepository.findAnyByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.anonymizeUser(USER_ID, buildPrincipal(ADMIN_ID));

            final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            then(userRepository).should().save(captor.capture());
            final User saved = captor.getValue();
            assertThat(saved.getEmail()).endsWith("@deleted.invalid");
            assertThat(saved.getPasswordHash()).isNull();
            assertThat(saved.isAnonymized()).isTrue();
            assertThat(saved.getAnonymizedAt()).isNotNull();
        }

        @Test
        @DisplayName("removes all team memberships before anonymizing")
        void removesTeamMemberships() {
            final User user = buildUser("ROLE_RESPONDER");
            user.archive();
            given(userRepository.findAnyByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.anonymizeUser(USER_ID, buildPrincipal(ADMIN_ID));

            then(teamMemberRepository).should().deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("throws 409 when user is not archived — must archive first")
        void throws409WhenNotArchived() {
            final User user = buildUser("ROLE_RESPONDER"); // active
            given(userRepository.findAnyByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    service.anonymizeUser(USER_ID, buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            then(teamMemberRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("throws 409 when user is already anonymized")
        void throws409WhenAlreadyAnonymized() {
            final User user = buildUser("ROLE_RESPONDER");
            user.archive();
            user.anonymize(UUID.randomUUID());
            given(userRepository.findAnyByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    service.anonymizeUser(USER_ID, buildPrincipal(ADMIN_ID)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private User buildUser(String role) {
        return User.forTesting(USER_ID, TENANT_ID, "u@example.com",
                "hash", true, List.of(role));
    }

    private UserPrincipal buildPrincipal(UUID userId) {
        return new UserPrincipal(userId, TENANT_ID, "admin@test.com",
                List.of("ROLE_ADMIN"), List.of());
    }
}