package com.incidentplatform.shared.security;

/**
 * A value that is not a {@link TenantIds valid tenant id} where one is required
 * (backlog #0-92): thrown by {@link TenantIds#requireValid}, so by
 * {@link TenantContext#set}, by {@code TenantRecords} when a record is built and
 * by {@code JwtUtils} when a token is issued. Its message states the rule, never
 * the rejected value.
 *
 * <p>An {@link IllegalArgumentException}, so every existing caller that handles
 * a bad argument handles this too; its own type lets a caller that must tell
 * "this row's tenant is wrong" from any other bad argument do so
 * ({@code AuditOutboxRelay} backs such a row off alone instead of pausing for
 * every tenant, found in review: it used to treat every
 * {@code IllegalArgumentException} that way).
 */
public class InvalidTenantIdException extends IllegalArgumentException {

    public InvalidTenantIdException() {
        super("Invalid tenant id: " + TenantIds.RULE);
    }
}
