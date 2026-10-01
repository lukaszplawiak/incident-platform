package com.incidentplatform.auth.service;

import com.incidentplatform.auth.bootstrap.TenantAdminReconciler;
import com.incidentplatform.auth.bootstrap.TenantAdminReconciler.Outcome;
import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.Tenant;
import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.dto.ProvisionTenantRequest;
import com.incidentplatform.auth.dto.ProvisionTenantResponse;
import com.incidentplatform.auth.dto.TenantDto;
import com.incidentplatform.auth.dto.TenantIds;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a platform operator onboards a customer: creates a tenant and invites its
 * first admin (backlog #0-80). Called by {@code PlatformTenantController}, which
 * lets only an admin of the {@link ReservedTenants#PLATFORM_OPERATOR} tenant in.
 *
 * <h2>Reverses part of backlog #0-16, deliberately and narrowly</h2>
 * #0-16 decided there would be no cross-tenant "create tenant" endpoint, so a
 * customer tenant's first admin came from {@code V1_1}'s seed: a known password
 * on every deployment. A multi-tenant platform has to onboard tenants at runtime,
 * and the standard way is an operator action (an identity provider's management
 * API, an organization created by the vendor's operations team). This class is
 * the platform's one cross-tenant capability, and it is kept narrow:
 * <ul>
 *   <li>it creates a tenant and its first admin, and reissues that admin's invite
 *       while nobody in the tenant can log in as an admin;</li>
 *   <li>it lists and shows tenants' metadata (id, name, dates, whether the first
 *       admin has accepted), never their data;</li>
 *   <li>it never sets a password: the admin sets their own by accepting the
 *       invite, exactly as for any user ({@link UserService#createUser});</li>
 *   <li>once the tenant has an admin, everything inside it is theirs; the
 *       operator cannot act in it.</li>
 * </ul>
 * Every action is audited twice: in the operator tenant ({@code TENANT_*}, who did
 * what to which tenant) and in the new tenant ({@code USER_CREATED},
 * {@code USER_INVITE_*}, by {@link UserService} and {@link ResendInviteService}).
 */
@Service
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    private static final Pattern SLUG = Pattern.compile(TenantIds.SLUG);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ResendInviteService resendInviteService;
    private final AuthTokenRepository authTokenRepository;
    private final AuthEmailOutboxRepository outboxRepository;
    private final AuditEventPublisher auditEventPublisher;

    public TenantProvisioningService(TenantRepository tenantRepository,
                                     UserRepository userRepository,
                                     UserService userService,
                                     ResendInviteService resendInviteService,
                                     AuthTokenRepository authTokenRepository,
                                     AuthEmailOutboxRepository outboxRepository,
                                     AuditEventPublisher auditEventPublisher) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.resendInviteService = resendInviteService;
        this.authTokenRepository = authTokenRepository;
        this.outboxRepository = outboxRepository;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Creates the tenant and invites its first admin, in one transaction: if
     * the invite cannot be created, the tenant row is rolled back too, so a
     * tenant never exists without its first admin.
     *
     * @throws BusinessException 400 for a malformed or reserved tenant id,
     *                           409 if a tenant with this id already exists
     */
    @Transactional
    public ProvisionTenantResponse provision(ProvisionTenantRequest request, UserPrincipal operator) {
        final String tenantId = request.tenantId();
        // The controller validates all of this too; the service holds the rules
        // for any other caller.
        if (isBlank(request.adminEmail()) || isBlank(request.displayName())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                    "adminEmail and displayName are required", HttpStatus.BAD_REQUEST);
        }
        // Trimmed only, like every other user's address (UserService does not
        // change its case, and login compares it exactly).
        final String email = request.adminEmail().trim();
        final String displayName = request.displayName().trim();
        if (tenantId == null || !SLUG.matcher(tenantId).matches()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                    "tenantId must be 3-63 lowercase letters, digits or hyphens, "
                            + "starting and ending with a letter or digit", HttpStatus.BAD_REQUEST);
        }
        if (ReservedTenants.isReserved(tenantId)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                    "Tenant id '" + tenantId + "' is reserved by the platform", HttpStatus.BAD_REQUEST);
        }
        if (tenantRepository.insertIfAbsent(tenantId, displayName, email,
                operator.userId()) == 0) {
            throw new BusinessException(ErrorCodes.ALREADY_EXISTS,
                    "Tenant '" + tenantId + "' already exists", HttpStatus.CONFLICT);
        }
        // Defence in depth (found in review): the tenants row is the guard, and
        // V21 backfilled it from every user, but a tenant id that somehow has
        // users without a row must not get a second admin from here. The
        // exception rolls the row back.
        if (userRepository.existsAnyByTenantId(tenantId)) {
            throw new BusinessException(ErrorCodes.ALREADY_EXISTS,
                    "Tenant id '" + tenantId + "' already has users", HttpStatus.CONFLICT);
        }

        final CreateUserResponse admin = inTenant(tenantId, () -> userService.createUser(
                new CreateUserRequest(email, List.of(SecurityRoles.ROLE_ADMIN))));

        auditEventPublisher.publishAuth(operator.userId(), ReservedTenants.PLATFORM_OPERATOR,
                AuditEventTypes.TENANT_PROVISIONED, "auth-service", operator.userId().toString(),
                "Tenant provisioned and its first admin invited",
                Map.of("tenantId", tenantId, "displayName", displayName,
                        "adminEmail", email, "adminUserId", admin.userId().toString()));
        log.info("Tenant provisioned: tenant={}, adminUserId={}, by operator={}",
                tenantId, admin.userId(), operator.userId());

        return new ProvisionTenantResponse(tenantId, displayName, admin.userId(), email);
    }

    /**
     * Reissues the first admin's invite of a provisioned tenant whose admin has
     * not accepted, when the invite email permanently failed or its token
     * expired. Uses the same goal-checking logic as the operator tenant's own
     * bootstrap ({@link TenantAdminReconciler}), so it never creates a second
     * admin and never touches a tenant that already has one.
     *
     * <p>It never creates a user either (found in review): provisioning always
     * created the first admin, so if that user is gone now, it was archived or
     * anonymized on purpose, and inviting someone into the tenant again would
     * revive a tenant its admins wound down. That needs a tenant status first
     * (backlog #0-82).
     *
     * @throws ResourceNotFoundException 404 if the tenant does not exist
     * @throws BusinessException         409 when there is nothing to reissue, or
     *                                   the tenant's users need a person to fix them
     */
    public void reissueFirstAdminInvite(String tenantId, UserPrincipal operator) {
        // Reserved tenants are not managed through this API, as provision()
        // refuses them: the operator tenant bootstraps its own admin
        // (OperatorTenantBootstrap), and this must not become a second route
        // into it (found in review). A rule about which tenants the API
        // manages, not an authorization decision (see ReservedTenants).
        if (ReservedTenants.isReserved(tenantId)) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Tenant '" + tenantId + "' is reserved by the platform and not managed "
                            + "through this API", HttpStatus.CONFLICT);
        }
        final Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        if (tenant.getFirstAdminEmail() == null) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Tenant '" + tenantId + "' was not provisioned through this API and has no "
                            + "recorded first admin", HttpStatus.CONFLICT);
        }
        if (userRepository.findByEmailAndTenantId(tenant.getFirstAdminEmail(), tenantId).isEmpty()) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "The first admin of tenant '" + tenantId + "' was archived or removed; an invite "
                            + "is not reissued into a tenant that was wound down (backlog #0-82)",
                    HttpStatus.CONFLICT);
        }
        final Outcome outcome = new TenantAdminReconciler(
                tenantId, tenant.getFirstAdminEmail(), "Tenant", "docs/tenant-provisioning.md",
                userRepository, authTokenRepository, outboxRepository, userService,
                resendInviteService).reconcile();

        switch (outcome) {
            case INVITED, REINVITED -> {
                auditEventPublisher.publishAuth(operator.userId(), ReservedTenants.PLATFORM_OPERATOR,
                        AuditEventTypes.TENANT_ADMIN_REINVITED, "auth-service",
                        operator.userId().toString(), "First admin invite reissued",
                        Map.of("tenantId", tenantId, "adminEmail", tenant.getFirstAdminEmail(),
                                "outcome", outcome.name()));
                log.info("First admin invite reissued: tenant={}, outcome={}, by operator={}",
                        tenantId, outcome, operator.userId());
            }
            case ADMIN_ACTIVE -> throw new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Tenant '" + tenantId + "' already has an active admin; its admins invite "
                            + "users themselves", HttpStatus.CONFLICT);
            case INVITE_IN_PROGRESS -> throw new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "The first admin's invite is still being sent or is still valid",
                    HttpStatus.CONFLICT);
            case CONFLICT -> throw new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "The users of tenant '" + tenantId + "' need a person to fix them before an "
                            + "invite can be reissued (docs/tenant-provisioning.md)", HttpStatus.CONFLICT);
            case FAILED, DISABLED -> throw new IllegalStateException(
                    "Reissuing the first admin invite failed for tenant " + tenantId
                            + " (outcome " + outcome + "); see the log above");
        }
    }

    /**
     * One tenant with whether its admin has accepted.
     *
     * @throws ResourceNotFoundException 404 if the tenant does not exist
     */
    @Transactional(readOnly = true)
    public TenantDto get(String tenantId) {
        final Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        return toDto(tenant, userRepository.existsActiveAcceptedUserWithRole(tenantId, Role.ROLE_ADMIN));
    }

    /** One page of tenants with whether each one's admin has accepted. */
    @Transactional(readOnly = true)
    public Page<TenantDto> list(Pageable pageable) {
        final Page<Tenant> page = tenantRepository.findAllByOrderByCreatedAtDescTenantIdAsc(pageable);
        final Set<String> withAdmin = page.isEmpty() ? Set.of() : new HashSet<>(
                userRepository.findTenantIdsWithActiveAcceptedUserWithRole(
                        page.map(Tenant::getTenantId).getContent(), Role.ROLE_ADMIN));
        return page.map(t -> toDto(t, withAdmin.contains(t.getTenantId())));
    }

    private static TenantDto toDto(Tenant tenant, boolean adminActive) {
        return new TenantDto(tenant.getTenantId(), tenant.getDisplayName(),
                tenant.getFirstAdminEmail(), adminActive, tenant.getCreatedAt(), tenant.getCreatedBy());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Runs {@code action} with {@link TenantContext} set to {@code tenantId}, then
     * restores the caller's (the operator's) tenant. {@link UserService} takes the
     * tenant of the user it creates from the context, as for any admin.
     */
    private static <T> T inTenant(String tenantId, java.util.function.Supplier<T> action) {
        final String previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
