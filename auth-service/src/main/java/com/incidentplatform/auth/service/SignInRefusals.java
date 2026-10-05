package com.incidentplatform.auth.service;

import com.incidentplatform.auth.ratelimit.BruteForceProtectionService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;

/**
 * Records a sign-in refused for a suspended tenant (backlog #0-82, step 2): an
 * audit event in the tenant's own trail and a counter,
 * {@value #COUNTER}{@code {flow, access, outcome}}.
 *
 * <p>Called by {@code SignInRefusalHandler} after the refused request's
 * transaction has rolled back, as {@code MfaService} audits a wrong code (backlog
 * #0-84): the event commits on its own, with one connection. A failure to write
 * it propagates, so the refusal becomes an error rather than an unaudited 403.
 *
 * <h2>Bounded per user (found in review)</h2>
 * The rollback gives the refused request its token back (an invite, a reset
 * link), and a password stays right, so the same sign-in can be repeated
 * without end, each repetition one more audit event. As for API keys (#0-89):
 * the action is bounded, never the audit. Each refusal counts against the
 * user's {@code SUSPENDED_SIGN_IN} lockout ({@code BruteForceProtectionService},
 * Redis, {@code brute-force-protection.max-failures} in its window); a user
 * over it gets {@link Outcome.Throttled} — 429 with Retry-After, no event,
 * counted as {@code outcome=throttled}. The scope is its own, so a resumed
 * tenant's users are not locked out of logging in. Like the login lockout it
 * fails open if Redis is down, and the check and the count are two Redis calls,
 * so parallel refusals can overshoot the limit a little; both accepted (README
 * "Infrastructure Hardening" gaps).
 */
@Service
public class SignInRefusals {

    private static final Logger log = LoggerFactory.getLogger(SignInRefusals.class);

    static final String COUNTER = "auth.signin.refused";
    static final String SOURCE = "auth-service";

    /** What became of a refusal. */
    public sealed interface Outcome {
        /** Audited and counted: answer 403. */
        record Recorded() implements Outcome { }

        /** Over the user's limit: answer 429 with this Retry-After, nothing audited. */
        record Throttled(Duration retryAfter) implements Outcome { }
    }

    private final AuditEventPublisher auditEventPublisher;
    private final BruteForceProtectionService bruteForceProtectionService;
    private final MeterRegistry meterRegistry;

    public SignInRefusals(AuditEventPublisher auditEventPublisher,
                          BruteForceProtectionService bruteForceProtectionService,
                          MeterRegistry meterRegistry) {
        this.auditEventPublisher = auditEventPublisher;
        this.bruteForceProtectionService = bruteForceProtectionService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @throws IllegalStateException if called inside a transaction: the event
     *         would roll back with the refusal's
     */
    public Outcome record(TenantSuspendedSignInException refusal) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A refused sign-in is recorded after its transaction, not inside it");
        }
        final String userKey = refusal.userId().toString();
        final BruteForceProtectionService.Scope scope = BruteForceProtectionService.Scope.SUSPENDED_SIGN_IN;
        if (bruteForceProtectionService.isLocked(scope, userKey, refusal.tenantId())) {
            count(refusal, "throttled");
            final Duration remaining = bruteForceProtectionService.getRemainingLockout(
                    scope, userKey, refusal.tenantId());
            return new Outcome.Throttled(remaining.toSeconds() < 1 ? Duration.ofSeconds(1) : remaining);
        }
        log.info("Sign-in refused, tenant suspended: tenant={}, user={}, flow={}, access={}",
                refusal.tenantId(), refusal.userId(), refusal.flow(), refusal.access());
        auditEventPublisher.publishAuth(
                refusal.userId(), refusal.tenantId(),
                AuditEventTypes.USER_SIGN_IN_REFUSED_TENANT_SUSPENDED,
                SOURCE,
                userKey,
                "Sign-in refused (" + refusal.flow() + "): the organisation's account is suspended",
                Map.of("flow", refusal.flow().name(), "access", refusal.access().name()));
        bruteForceProtectionService.recordFailure(scope, userKey, refusal.tenantId());
        count(refusal, "audited");
        return new Outcome.Recorded();
    }

    private void count(TenantSuspendedSignInException refusal, String outcome) {
        Counter.builder(COUNTER)
                .description("Sign-ins refused because the tenant is suspended (backlog #0-82)")
                .tag("flow", refusal.flow().name())
                .tag("access", refusal.access().name())
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
