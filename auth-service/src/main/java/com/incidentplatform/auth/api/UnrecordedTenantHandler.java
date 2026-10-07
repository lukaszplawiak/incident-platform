package com.incidentplatform.auth.api;

import com.incidentplatform.shared.dto.ErrorResponse;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.GlobalExceptionHandler;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Answers a write refused by one of V31's foreign keys to {@code tenants}
 * (backlog #0-82) with 403, not the shared catch-all's 500.
 *
 * <p>Since V31 every auth-service table holding a tenant's data needs the
 * tenant's row. A signed-in request whose tenant has no row can only carry a
 * token minted outside auth-service's sign-in ({@code /dev/token} in dev,
 * elsewhere something holding the JWT secret): {@code TenantAccessService}
 * lets it through with full access, counted and alerted
 * ({@code PlatformTenantStatusRowMissing}), and its first write then fails on
 * the foreign key. Found in the review: that reached
 * {@code GlobalExceptionHandler}'s catch-all as a 500 with a stack trace in the
 * log, though nothing is broken and nothing is retryable; the tenant does not
 * exist here. So 403 {@code FORBIDDEN}, logged at WARN with the constraint's
 * name only (the message carries the SQL and the values). The body says no more
 * than any other refusal, word for word the shared 403 (second review): naming
 * the reason would tell whoever holds such a token which tenant ids the
 * platform knows.
 *
 * <p>Only these nine constraints: any other integrity error is a bug, and
 * gets exactly the shared catch-all's answer, as without this handler.
 * Ordered first, like {@link TenantStatusBusyHandler}: Spring picks the first
 * advice with any matching handler, and the shared one matches everything.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UnrecordedTenantHandler {

    private static final Logger log = LoggerFactory.getLogger(UnrecordedTenantHandler.class);

    /** V31's constraint names; {@code AuthRepositoryIntegrationTest} checks they are all there is. */
    public static final Set<String> TENANT_FOREIGN_KEYS = Set.of(
            "fk_users_tenant", "fk_user_roles_tenant", "fk_teams_tenant", "fk_tenant_settings_tenant",
            "fk_auth_tokens_tenant", "fk_api_keys_tenant", "fk_integrations_tenant",
            "fk_slack_workspaces_tenant", "fk_mfa_recovery_requests_tenant");

    private final GlobalExceptionHandler fallback = new GlobalExceptionHandler();

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrityViolation(DataIntegrityViolationException e)
            throws NoResourceFoundException {
        final Optional<String> constraint = tenantForeignKey(e);
        if (constraint.isEmpty()) {
            return fallback.handleUnexpectedException(e);
        }
        log.warn("Write refused: the request's tenant has no row in tenants, constraint={}", constraint.get());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN.value(), ErrorCodes.FORBIDDEN,
                        "You do not have permission to perform this action.",
                        Objects.requireNonNullElse(MDC.get("requestId"), "unknown")));
    }

    /** The V31 constraint the exception names, if it names one. */
    public static Optional<String> tenantForeignKey(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                final String name = cve.getConstraintName().toLowerCase(java.util.Locale.ROOT);
                return TENANT_FOREIGN_KEYS.contains(name) ? Optional.of(name) : Optional.empty();
            }
        }
        return Optional.empty();
    }
}
