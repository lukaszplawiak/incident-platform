package com.incidentplatform.auth.bootstrap;

import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates the first admin of the reserved {@link ReservedTenants#PLATFORM_OPERATOR}
 * tenant, by invite, when {@code platform.operator.bootstrap.admin-email} is set
 * (backlog #0-16).
 *
 * <h2>Why an invite, run at startup</h2>
 * The operator tenant is where the platform's own Alertmanager files its alerts
 * as incidents. Nobody can create it through the API (tenant ids are reserved,
 * and there is deliberately no cross-tenant "create tenant" endpoint), so the
 * platform creates its first user itself, the same way an admin creates any
 * user: {@link UserService#createUser}, which writes the user, the invite token
 * and the invite email in one transaction and audits it. No password ever sits
 * in configuration; the operator sets it by accepting the invite.
 * Alternatives rejected: a Flyway seed with a password from env (a skipped
 * conditional migration is recorded as applied, it cannot use the outbox or
 * audit, and it repeats {@code V1_1}'s plaintext-password weakness), and a
 * platform super-admin endpoint (a cross-tenant capability).
 *
 * <h2>Idempotent</h2>
 * Does nothing once the operator tenant has any user, so it is safe on every
 * start and every replica. If two replicas race, the loser's duplicate-email
 * conflict is logged at INFO. The Integration API key for Alertmanager is not
 * created here: the operator admin creates it with {@code POST
 * /api/v1/integrations} and stores it as a secret, so no service writes
 * credentials to disk.
 */
@Component
public class OperatorTenantBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OperatorTenantBootstrap.class);

    private final String adminEmail;
    private final UserRepository userRepository;
    private final UserService userService;

    public OperatorTenantBootstrap(
            @Value("${platform.operator.bootstrap.admin-email:}") String adminEmail,
            UserRepository userRepository,
            UserService userService) {
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim();
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail.isEmpty()) {
            log.debug("Operator tenant bootstrap disabled (platform.operator.bootstrap.admin-email unset)");
            return;
        }
        if (userRepository.existsByTenantId(ReservedTenants.PLATFORM_OPERATOR)) {
            log.debug("Operator tenant already has users — bootstrap not needed");
            return;
        }

        TenantContext.set(ReservedTenants.PLATFORM_OPERATOR);
        try {
            userService.createUser(new CreateUserRequest(
                    adminEmail, List.of(SecurityRoles.ROLE_ADMIN)));
            log.info("Operator tenant bootstrapped: invite sent to the first admin, tenant={}",
                    ReservedTenants.PLATFORM_OPERATOR);
        } catch (BusinessException e) {
            if (!ErrorCodes.EMAIL_ALREADY_EXISTS.equals(e.getErrorCode())) {
                throw e;
            }
            log.info("Operator tenant already bootstrapped by another replica");
        } catch (DataIntegrityViolationException e) {
            log.info("Operator tenant already bootstrapped by another replica ({})",
                    e.getMostSpecificCause().getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
