package com.incidentplatform.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auth Service — identity, access management, and organisational configuration
 * for the Incident Platform.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Authentication — login, logout, JWT refresh, token revocation, MFA (TOTP)</li>
 *   <li>Password lifecycle — forgot/reset/change password</li>
 *   <li>User invitation flow — invite emails, accept invite, resend invite</li>
 *   <li>User management — CRUD, roles, archive, GDPR anonymization</li>
 *   <li>Team management — team and membership management</li>
 *   <li>API Keys — long-lived credentials for machine-to-machine integrations</li>
 *   <li>Integrations — named alert source → team routing connections</li>
 *   <li>Tenant settings — per-tenant configuration (e.g. MFA enforcement policy)</li>
 * </ul>
 *
 * <h2>Architecture note — intentional modular monolith</h2>
 * This service is deliberately larger than other services in this platform.
 * All identity and organisational configuration concerns are colocated here
 * to avoid distributed transaction complexity and HTTP latency on the login
 * hot path (AuthService.login() needs User credentials in the same transaction).
 *
 * <h2>TODO (Backlog): Future service split</h2>
 * When the system reaches a scale where independent deployment or independent
 * scaling of authentication vs identity management is required, this service
 * should be split into:
 *
 * <pre>
 *   auth-service     → authentication (login, logout, MFA, password, invites)
 *   identity-service → organisational identity (users, teams, tenant settings)
 *   apikey-service   → machine-to-machine credentials (optional)
 * </pre>
 *
 * <p><b>Key challenges to solve before splitting:</b>
 *
 * <ol>
 *   <li><b>User entity dependency on login path</b> —
 *       {@code AuthService.login()} fetches {@code User} (email, passwordHash,
 *       tenantId, roles) from the database in the same transaction.
 *       After split, auth-service would need to call identity-service via HTTP
 *       on every login — adding latency and a single point of failure.
 *       <br>
 *       <i>Recommended solution:</i> Redis credential cache populated by
 *       identity-service Kafka events ({@code UserCredentialsUpdatedEvent}),
 *       invalidated on password change or user archive. Cache hit = zero HTTP
 *       call on login path. This is the pattern used by Auth0 internally.
 *   </li>
 *
 *   <li><b>Distributed transaction in invite flow</b> —
 *       {@code InviteService.createUser()} creates {@code User} and
 *       {@code AuthEmailOutbox} in a single local transaction.
 *       After split (User in identity-service, AuthEmailOutbox in auth-service)
 *       this becomes a distributed transaction.
 *       <br>
 *       <i>Recommended solution:</i> Outbox Pattern (already used in this project
 *       for postmortem-service and notification-service). identity-service writes
 *       {@code UserInviteCreatedEvent} to its own outbox; auth-service consumes
 *       the event and creates the AuthEmailOutbox entry independently.
 *   </li>
 *
 *   <li><b>Database separation</b> —
 *       Currently one PostgreSQL database shared by all auth-service domains.
 *       Split requires either separate databases (full isolation) or a
 *       Shared Database pattern as a first step (both services read from one DB,
 *       lower risk, easier migration).
 *   </li>
 * </ol>
 *
 * <p><b>Split when:</b> independent scaling is needed, separate teams own auth
 * vs identity, or compliance requirements (PCI-DSS, SOC2) mandate credential
 * isolation. <b>Do NOT split prematurely</b> — the cost of distributed
 * transactions and HTTP latency on the login path must be justified by
 * concrete operational requirements.
 *
 * @see <a href="https://martinfowler.com/articles/break-monolith-into-microservices.html">
 *     Strangler Fig pattern — incremental service extraction</a>
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@ComponentScan(basePackages = {
        "com.incidentplatform.auth",
        "com.incidentplatform.shared"
})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}