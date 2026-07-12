package com.incidentplatform.auth.domain;

/**
 * Available roles in the Incident Platform.
 *
 * <h2>Why enum instead of CHECK constraint</h2>
 * Previously {@code user_roles.role} had a database CHECK constraint:
 * {@code CHECK (role IN ('ROLE_ADMIN', 'ROLE_RESPONDER'))}.
 * Adding a new role required an {@code ALTER TABLE DROP CONSTRAINT} +
 * {@code ADD CONSTRAINT} — a risky DDL operation on a production table
 * with data. With an enum, adding a new role is a one-line Java change
 * and a data migration (INSERT new rows), never a schema constraint change.
 *
 * <h2>Naming convention</h2>
 * The {@code ROLE_} prefix is required by Spring Security's
 * {@code hasRole()} / {@code ROLE_} authority matching convention.
 * {@link User#getRoleNames()} returns these as strings for JWT claims.
 *
 * <h2>Adding a new role</h2>
 * <ol>
 *   <li>Add the value here</li>
 *   <li>Add a Flyway migration granting the new role to relevant users</li>
 *   <li>Add {@code .hasRole(...)} rules in {@code SecurityConfig} if needed</li>
 * </ol>
 */
public enum Role {

    /** Full administrative access — user management, system configuration. */
    ROLE_ADMIN,

    /** Incident responder — can view, acknowledge, and resolve incidents. */
    ROLE_RESPONDER
}