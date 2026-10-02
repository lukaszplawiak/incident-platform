package com.incidentplatform.auth.breakglass;

import com.incidentplatform.auth.service.MfaService;
import com.incidentplatform.shared.audit.AuditOutboxRelay;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * The break-glass MFA reset (backlog #0-88): auth-service started once, as a
 * command, to reset the second factor of a platform operator admin who has
 * no other operator admin to do it through {@code POST /api/v1/users/{id}/mfa-reset}.
 *
 * <pre>
 * docker compose run --rm auth-service break-glass-mfa-reset \
 *   --break-glass.mfa-reset.user-email=ops@example.com \
 *   --break-glass.mfa-reset.actor="Jane Doe" \
 *   --break-glass.mfa-reset.reason="lost phone and backup codes, confirmed by call"
 * </pre>
 *
 * <h2>Why a command, not SQL</h2>
 * The same code path as the admin reset ({@code MfaService.resetMfaBreakGlass}),
 * so the account is emailed and the action audited, as
 * {@code MFA_RESET_BREAK_GLASS} with the operator's name and reason. A
 * database change could do neither. The pattern of GitLab's rake tasks or
 * Django's management commands: whoever can run the service with its
 * credentials may run it, the same trust as database access, and it leaves
 * the trail the database would not.
 *
 * <h2>Process</h2>
 * Exits with 0 after a reset, 1 when it refused or failed (nothing changed),
 * 2 if it finds itself in a web server context (a safeguard: main never
 * starts one for the command). The runner only records the code
 * ({@link ExitCodeGenerator}); {@code AuthServiceApplication.main} ends the
 * process, so nothing here calls {@code System.exit}. Only the subcommand
 * {@value BreakGlassCommand#SUBCOMMAND} starts it ({@link BreakGlassCommand}):
 * {@code main} then starts no web server, so the one-off process serves no
 * requests, and {@code SchedulerConfig} turns scheduling off
 * whenever this command is given, so it takes no ShedLock lock that an exit
 * could leave held for minutes. The email is sent by the running
 * auth-service's outbox scheduler.
 *
 * <h2>The audit event (backlog #0-84)</h2>
 * Written to {@code auth_audit_outbox} in the reset's transaction: no event,
 * no reset. After the commit the command runs the outbox relay once
 * ({@code AuditOutboxRelay.relayNow}, under the relay's own lock, released
 * when it returns; at most one batch timeout while Kafka is unreachable) and
 * logs whether the event reached Kafka. If not, the running auth-service
 * sends it, and {@code AuditOutboxBacklog} alerts the operator when it waits
 * over 10 minutes ({@code PlatformServiceDown} when no auth-service runs).
 * Until #0-84 the command waited for Kafka's acknowledgement inside the
 * transaction and rolled the reset back without it; the reset no longer
 * depends on Kafka, its audit event still on the database.
 */
@Component
@Conditional(BreakGlassCommand.class)
public class BreakGlassMfaResetRunner implements ApplicationRunner, ExitCodeGenerator {

    static final String PREFIX = "break-glass.mfa-reset";

    /** Until {@link #run} finishes, the command has not succeeded. */
    static final int NOT_RUN = 1;

    private static final Logger log = LoggerFactory.getLogger(BreakGlassMfaResetRunner.class);

    private final MfaService mfaService;
    private final ObjectProvider<AuditOutboxRelay> auditRelay;
    private final ApplicationContext context;
    private final String userEmail;
    private final String actor;
    private final String reason;

    private volatile int exitCode = NOT_RUN;

    public BreakGlassMfaResetRunner(
            MfaService mfaService,
            ObjectProvider<AuditOutboxRelay> auditRelay,
            ApplicationContext context,
            @Value("${" + PREFIX + ".user-email:}") String userEmail,
            @Value("${" + PREFIX + ".actor:}") String actor,
            @Value("${" + PREFIX + ".reason:}") String reason) {
        this.mfaService = mfaService;
        this.auditRelay = auditRelay;
        this.context = context;
        this.userEmail = userEmail;
        this.actor = actor;
        this.reason = reason;
    }

    /**
     * Whether this context was started as the break-glass command, so
     * {@code AuthServiceApplication.main} ends the process with
     * {@code SpringApplication.exit}, which collects {@link #getExitCode}
     * (Spring Boot's {@code ExitCodeGenerator}). The service itself never exits.
     */
    public static boolean isCommand(ApplicationContext context) {
        return !context.getBeansOfType(BreakGlassMfaResetRunner.class).isEmpty();
    }

    @Override
    public void run(ApplicationArguments args) {
        exitCode = execute();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /**
     * Where the command runs, for the audit event (review of #0-88: the actor
     * is whatever name the operator types): the OS user and the host name.
     * Advisory, not proof of identity: whoever starts the process can set
     * {@code -Duser.name}; it records the context, and the trust model is
     * that of starting the process at all. Nothing here can fail the command.
     */
    static String executedOn() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = null;
        }
        return formatExecutedOn(System.getProperty("user.name"), host);
    }

    /**
     * {@code user@host}, an unknown or blank part named as such, cut to the
     * length the service accepts ({@link MfaService#BREAK_GLASS_EXECUTED_ON_MAX})
     * by shortening the host (found in review: a 253-character host name would
     * otherwise refuse the only recovery path).
     */
    static String formatExecutedOn(String user, String host) {
        final String u = user == null || user.isBlank() ? "unknown-user" : user.strip();
        final String h = host == null || host.isBlank() ? "unknown-host" : host.strip();
        final String full = u + "@" + h;
        return full.length() <= MfaService.BREAK_GLASS_EXECUTED_ON_MAX
                ? full : full.substring(0, MfaService.BREAK_GLASS_EXECUTED_ON_MAX);
    }

    private int execute() {
        if (context instanceof WebServerApplicationContext) {
            log.error("Break-glass MFA reset refused: a web server is running. AuthServiceApplication.main "
                    + "starts the command without one; was the context started another way?");
            return 2;
        }
        if (userEmail == null || userEmail.isBlank()) {
            log.error("Break-glass MFA reset refused: --{}.user-email is required", PREFIX);
            return 1;
        }
        // The tenant of this command, as a request filter would set it: the
        // tenant in the log lines' MDC, and in anything tenant-scoped the reset
        // calls. (The audit record's X-Tenant-Id header is set by
        // AuditEventKafkaSender from the event itself, backlog #0-88.)
        TenantContext.set(ReservedTenants.PLATFORM_OPERATOR);
        try {
            final UUID userId = mfaService.resetMfaBreakGlass(userEmail, actor, reason, executedOn());
            log.warn("Break-glass MFA reset done: userId={}, tenant={}. The account is emailed by the running "
                    + "auth-service (email outbox).", userId, ReservedTenants.PLATFORM_OPERATOR);
            sendAuditEventNow();
            return 0;
        } catch (RuntimeException e) {
            log.error("Break-glass MFA reset failed, nothing was changed: {}", e.getMessage(), e);
            return 1;
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Sends the committed audit event now when Kafka is reachable. The reset
     * is done either way: an event left in the outbox is sent by the running
     * service (see the class Javadoc), so nothing here changes the exit code.
     */
    private void sendAuditEventNow() {
        final AuditOutboxRelay relay = auditRelay.getIfAvailable();
        if (relay == null) {
            log.error("No audit outbox configured (audit.outbox.table): the MFA_RESET_BREAK_GLASS event was "
                    + "sent the old way, without confirmation");
            return;
        }
        try {
            final AuditOutboxRelay.RunResult result = relay.relayNow();
            if (result.failed() == 0 && result.sent() > 0) {
                log.warn("MFA_RESET_BREAK_GLASS audit event sent to Kafka ({} event(s) from the outbox).",
                        result.sent());
                return;
            }
            log.warn("MFA_RESET_BREAK_GLASS audit event not sent now ({} sent, {} failed; Kafka unreachable, or "
                    + "a running auth-service holds the relay lock). It is stored in auth_audit_outbox: the running "
                    + "auth-service sends it, and AuditOutboxBacklog alerts if it waits over 10 minutes.",
                    result.sent(), result.failed());
        } catch (RuntimeException e) {
            log.warn("MFA_RESET_BREAK_GLASS audit event not sent now; it is stored in auth_audit_outbox and the "
                    + "running auth-service sends it.", e);
        }
    }
}
