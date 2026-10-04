package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.Tenant;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.dto.ProvisionTenantRequest;
import com.incidentplatform.auth.dto.ProvisionTenantResponse;
import com.incidentplatform.auth.dto.TenantDto;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.TenantRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantProvisioningService — operator onboards a tenant (backlog #0-80)")
class TenantProvisioningServiceTest {

    private static final String TENANT = "acme";
    private static final String EMAIL = "admin@acme.test";
    private static final UUID OPERATOR_ID = UUID.randomUUID();
    private static final UserPrincipal OPERATOR = new UserPrincipal(OPERATOR_ID,
            ReservedTenants.PLATFORM_OPERATOR, "ops@platform.test",
            List.of(SecurityRoles.ROLE_ADMIN), List.of());

    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserService userService;
    @Mock private ResendInviteService resendInviteService;
    @Mock private AuthTokenRepository authTokenRepository;
    @Mock private AuthEmailOutboxRepository outboxRepository;
    @Mock private AuditEventPublisher auditEventPublisher;

    private TenantProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new TenantProvisioningService(tenantRepository, userRepository, userService,
                resendInviteService, authTokenRepository, outboxRepository, auditEventPublisher);
        // As in a request: JwtAuthFilter set the operator's tenant.
        TenantContext.set(ReservedTenants.PLATFORM_OPERATOR);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("provision")
    class Provision {

        private ProvisionTenantRequest request(String tenantId) {
            return new ProvisionTenantRequest(tenantId, " Acme Corp ", " " + EMAIL + " ");
        }

        @Test
        @DisplayName("creates the tenant, invites the admin inside it, audits in the operator tenant")
        void provisions() {
            final UUID adminId = UUID.randomUUID();
            final AtomicReference<String> tenantDuringCreate = new AtomicReference<>();
            given(tenantRepository.insertIfAbsent(TENANT, "Acme Corp", EMAIL, OPERATOR_ID)).willReturn(1);
            willAnswer(inv -> {
                tenantDuringCreate.set(TenantContext.get());
                return new CreateUserResponse(adminId, TENANT, EMAIL,
                        List.of(SecurityRoles.ROLE_ADMIN), true, Instant.now());
            }).given(userService).createUser(new CreateUserRequest(EMAIL, List.of(SecurityRoles.ROLE_ADMIN)));

            final ProvisionTenantResponse response = service.provision(request(TENANT), OPERATOR);

            assertThat(response).isEqualTo(new ProvisionTenantResponse(TENANT, "Acme Corp", adminId, EMAIL));
            assertThat(tenantDuringCreate.get()).as("the admin is created in the new tenant").isEqualTo(TENANT);
            assertThat(TenantContext.get()).as("the operator's tenant is restored")
                    .isEqualTo(ReservedTenants.PLATFORM_OPERATOR);
            then(auditEventPublisher).should().publishAuth(eq(OPERATOR_ID),
                    eq(ReservedTenants.PLATFORM_OPERATOR), eq(AuditEventTypes.TENANT_PROVISIONED),
                    eq("auth-service"), eq(OPERATOR_ID.toString()), anyString(),
                    eq(Map.of("tenantId", TENANT, "displayName", "Acme Corp",
                            "adminEmail", EMAIL, "adminUserId", adminId.toString())));
        }

        @Test
        @DisplayName("refuses a missing admin email or display name with 400, before writing anything")
        void blankFields() {
            for (final ProvisionTenantRequest bad : List.of(
                    new ProvisionTenantRequest(TENANT, "Acme", null),
                    new ProvisionTenantRequest(TENANT, "Acme", "  "),
                    new ProvisionTenantRequest(TENANT, null, EMAIL))) {
                assertThatThrownBy(() -> service.provision(bad, OPERATOR))
                        .isInstanceOfSatisfying(BusinessException.class,
                                e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
            }
            verifyNoInteractions(tenantRepository, userService, auditEventPublisher);
        }

        @Test
        @DisplayName("refuses an id that already has users (archived ones too) with 409, creating no admin")
        void idWithUsersRefused() {
            given(tenantRepository.insertIfAbsent(any(), any(), any(), any())).willReturn(1);
            given(userRepository.existsAnyByTenantId(TENANT)).willReturn(true);

            assertThatThrownBy(() -> service.provision(request(TENANT), OPERATOR))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT));
            verifyNoInteractions(userService, auditEventPublisher);
        }

        @ParameterizedTest
        @ValueSource(strings = {"Acme", "ac", "-acme", "acme-", "ac_me", "acme.corp"})
        @DisplayName("refuses a malformed tenant id with 400, before writing anything")
        void malformed(String tenantId) {
            assertThatThrownBy(() -> service.provision(request(tenantId), OPERATOR))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
                    });
            verifyNoInteractions(tenantRepository, userService, auditEventPublisher);
        }

        @ParameterizedTest
        @ValueSource(strings = {ReservedTenants.PLATFORM_OPERATOR, ReservedTenants.LEGACY_SYSTEM})
        @DisplayName("refuses a reserved tenant id with 400")
        void reserved(String tenantId) {
            assertThatThrownBy(() -> service.provision(request(tenantId), OPERATOR))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
            verifyNoInteractions(tenantRepository, userService, auditEventPublisher);
        }

        @Test
        @DisplayName("refuses an existing tenant id with 409 and creates no user in it")
        void existing() {
            given(tenantRepository.insertIfAbsent(any(), any(), any(), any())).willReturn(0);

            assertThatThrownBy(() -> service.provision(request(TENANT), OPERATOR))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCodes.ALREADY_EXISTS);
                    });
            verifyNoInteractions(userService, auditEventPublisher);
        }

        @Test
        @DisplayName("a failing invite propagates (rolling the tenant back) and restores the context")
        void createUserFails() {
            given(tenantRepository.insertIfAbsent(any(), any(), any(), any())).willReturn(1);
            given(userService.createUser(any())).willThrow(new IllegalStateException("outbox down"));

            assertThatThrownBy(() -> service.provision(request(TENANT), OPERATOR))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(TenantContext.get()).isEqualTo(ReservedTenants.PLATFORM_OPERATOR);
            verifyNoInteractions(auditEventPublisher);
        }

        @Test
        @DisplayName("clears the context afterwards when the caller had none")
        void noCallerContext() {
            TenantContext.clear();
            given(tenantRepository.insertIfAbsent(any(), any(), any(), any())).willReturn(1);
            given(userService.createUser(any())).willReturn(new CreateUserResponse(UUID.randomUUID(),
                    TENANT, EMAIL, List.of(SecurityRoles.ROLE_ADMIN), true, Instant.now()));

            service.provision(request(TENANT), OPERATOR);

            assertThat(TenantContext.getOrNull()).isNull();
        }
    }

    @Nested
    @DisplayName("reissueFirstAdminInvite")
    class Reissue {

        private void tenant(String firstAdminEmail) {
            tenant(firstAdminEmail, com.incidentplatform.auth.domain.TenantStatus.ACTIVE);
        }

        private void tenant(String firstAdminEmail, com.incidentplatform.auth.domain.TenantStatus status) {
            final Tenant tenant = mock(Tenant.class);
            org.mockito.Mockito.lenient().when(tenant.getFirstAdminEmail()).thenReturn(firstAdminEmail);
            given(tenant.getStatus()).willReturn(status);
            given(tenantRepository.findById(TENANT)).willReturn(Optional.of(tenant));
        }

        @org.junit.jupiter.api.Test
        @DisplayName("refuses to reissue an invite into a suspended tenant (backlog #0-82)")
        void suspendedTenantRefused() {
            tenant(EMAIL, com.incidentplatform.auth.domain.TenantStatus.SUSPENDED);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.reissueFirstAdminInvite(TENANT, OPERATOR))
                    .isInstanceOf(com.incidentplatform.shared.exception.BusinessException.class)
                    .hasMessageContaining("resume it");
            org.mockito.BDDMockito.then(userRepository).shouldHaveNoInteractions();
        }

        private User pendingAdmin() {
            final User user = User.forTesting(UUID.randomUUID(), TENANT, EMAIL, null, true,
                    List.of(SecurityRoles.ROLE_ADMIN));
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT)).willReturn(Optional.of(user));
            return user;
        }

        private void latestInvite(User user, AuthEmailStatus status) {
            final AuthEmailOutbox entry = mock(AuthEmailOutbox.class);
            given(entry.getStatus()).willReturn(status);
            given(outboxRepository.findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(
                    user.getId(), AuthEmailType.INVITE)).willReturn(Optional.of(entry));
        }

        private void assertConflict(Runnable call) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT));
            verifyNoInteractions(auditEventPublisher);
        }

        @ParameterizedTest
        @ValueSource(strings = {ReservedTenants.PLATFORM_OPERATOR, ReservedTenants.LEGACY_SYSTEM})
        @DisplayName("409 for a reserved tenant, before looking anything up")
        void reservedTenant(String tenantId) {
            assertConflict(() -> service.reissueFirstAdminInvite(tenantId, OPERATOR));
            verifyNoInteractions(tenantRepository, userRepository, userService, resendInviteService);
        }

        @Test
        @DisplayName("404 for an unknown tenant")
        void unknownTenant() {
            given(tenantRepository.findById(TENANT)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.reissueFirstAdminInvite(TENANT, OPERATOR))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("409 for a backfilled tenant with no recorded first admin")
        void backfilledTenant() {
            tenant(null);

            assertConflict(() -> service.reissueFirstAdminInvite(TENANT, OPERATOR));
            verifyNoInteractions(userRepository, userService, resendInviteService);
        }

        @Test
        @DisplayName("409 once the tenant has an active admin")
        void adminActive() {
            tenant(EMAIL);
            pendingAdmin();
            given(userRepository.existsActiveAcceptedUserWithRole(TENANT, Role.ROLE_ADMIN)).willReturn(true);

            assertConflict(() -> service.reissueFirstAdminInvite(TENANT, OPERATOR));
            verifyNoInteractions(userService, resendInviteService);
        }

        @Test
        @DisplayName("409 while the invite is still being sent")
        void inviteInProgress() {
            tenant(EMAIL);
            latestInvite(pendingAdmin(), AuthEmailStatus.PENDING);

            assertConflict(() -> service.reissueFirstAdminInvite(TENANT, OPERATOR));
            then(resendInviteService).should(never()).resendInvite(any());
        }

        @Test
        @DisplayName("409 when the first admin exists but cannot log in as an active admin")
        void conflict() {
            tenant(EMAIL);
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT)).willReturn(Optional.of(
                    User.forTesting(UUID.randomUUID(), TENANT, EMAIL, null, false,
                            List.of(SecurityRoles.ROLE_ADMIN))));

            assertConflict(() -> service.reissueFirstAdminInvite(TENANT, OPERATOR));
            verifyNoInteractions(userService, resendInviteService);
        }

        @Test
        @DisplayName("re-invites when the invite email permanently failed, audited, context restored")
        void reinvites() {
            tenant(EMAIL);
            final User user = pendingAdmin();
            latestInvite(user, AuthEmailStatus.PERMANENTLY_FAILED);
            final AtomicReference<String> tenantDuringResend = new AtomicReference<>();
            willAnswer(inv -> {
                tenantDuringResend.set(TenantContext.get());
                return null;
            }).given(resendInviteService).resendInvite(user.getId());

            service.reissueFirstAdminInvite(TENANT, OPERATOR);

            assertThat(tenantDuringResend.get()).isEqualTo(TENANT);
            assertThat(TenantContext.get()).isEqualTo(ReservedTenants.PLATFORM_OPERATOR);
            then(auditEventPublisher).should().publishAuth(eq(OPERATOR_ID),
                    eq(ReservedTenants.PLATFORM_OPERATOR), eq(AuditEventTypes.TENANT_ADMIN_REINVITED),
                    eq("auth-service"), eq(OPERATOR_ID.toString()), anyString(),
                    eq(Map.of("tenantId", TENANT, "adminEmail", EMAIL, "outcome", "REINVITED")));
        }

        @Test
        @DisplayName("409 and no user created when the first admin was archived or removed")
        void firstAdminGoneIsRefused() {
            tenant(EMAIL);
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT)).willReturn(Optional.empty());

            assertConflict(() -> service.reissueFirstAdminInvite(TENANT, OPERATOR));
            verifyNoInteractions(userService, resendInviteService);
        }

        @Test
        @DisplayName("a failure becomes a 500 (IllegalStateException), not a silent success")
        void failure() {
            tenant(EMAIL);
            final User user = pendingAdmin();
            latestInvite(user, AuthEmailStatus.PERMANENTLY_FAILED);
            willThrow(new IllegalStateException("db down")).given(resendInviteService)
                    .resendInvite(user.getId());

            assertThatThrownBy(() -> service.reissueFirstAdminInvite(TENANT, OPERATOR))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FAILED");
            verifyNoInteractions(auditEventPublisher);
        }
    }

    @Nested
    @DisplayName("list")
    class ListTenants {

        private Tenant tenant(String id, String email) {
            final Tenant tenant = mock(Tenant.class);
            given(tenant.getTenantId()).willReturn(id);
            given(tenant.getDisplayName()).willReturn(id.toUpperCase());
            given(tenant.getFirstAdminEmail()).willReturn(email);
            return tenant;
        }

        @Test
        @DisplayName("marks which tenants have an active admin, with one query for the page")
        void marksAdminActive() {
            final PageRequest pageable = PageRequest.of(0, 20);
            final List<Tenant> tenants = List.of(tenant("acme", EMAIL), tenant("globex", null));
            given(tenantRepository.findAllByOrderByCreatedAtDescTenantIdAsc(pageable))
                    .willReturn(new PageImpl<>(tenants, pageable, 2));
            given(userRepository.findTenantIdsWithActiveAcceptedUserWithRole(
                    List.of("acme", "globex"), Role.ROLE_ADMIN)).willReturn(List.of("globex"));

            final Page<TenantDto> page = service.list(pageable);

            assertThat(page.getContent()).extracting(TenantDto::tenantId, TenantDto::adminActive,
                            TenantDto::firstAdminEmail)
                    .containsExactly(
                            tuple("acme", false, EMAIL),
                            tuple("globex", true, null));
        }

        @Test
        @DisplayName("get: one tenant with its admin state; 404 when missing")
        void getOne() {
            final Tenant acme = tenant("acme", EMAIL);
            given(tenantRepository.findById("acme")).willReturn(Optional.of(acme));
            given(userRepository.existsActiveAcceptedUserWithRole("acme", Role.ROLE_ADMIN)).willReturn(true);
            given(tenantRepository.findById("nope")).willReturn(Optional.empty());

            assertThat(service.get("acme")).extracting(TenantDto::tenantId, TenantDto::adminActive)
                    .containsExactly("acme", true);
            assertThatThrownBy(() -> service.get("nope")).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("an empty page queries no users")
        void emptyPage() {
            final PageRequest pageable = PageRequest.of(3, 20);
            given(tenantRepository.findAllByOrderByCreatedAtDescTenantIdAsc(pageable))
                    .willReturn(Page.empty(pageable));

            assertThat(service.list(pageable)).isEmpty();
            verifyNoInteractions(userRepository);
        }
    }
}
