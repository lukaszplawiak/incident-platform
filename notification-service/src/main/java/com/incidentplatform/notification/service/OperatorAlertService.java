package com.incidentplatform.notification.service;

import com.incidentplatform.notification.channel.NotificationChannel;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.domain.UndeliverableReason;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.notification.router.NotificationChannels;
import com.incidentplatform.shared.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells the platform <em>operator</em> that a notification could not be
 * delivered to anyone in its tenant (backlog #0-18).
 *
 * <h2>Why content-free, and why the operator</h2>
 * The incident text (title, severity) belongs to the tenant, and the only
 * people allowed to receive it are members of that tenant. When nobody in the
 * tenant can be notified, the text is therefore not sent anywhere. What the
 * operator needs is to know that it happened and where to look: the tenant id,
 * the incident id, the event type and the reason. Operator telemetry carrying a
 * tenant identifier is standard practice; customer content in it is not.
 *
 * <p>The destination is {@code notification.operator-alert.email}, which
 * belongs to the operator and has no default. If it is not configured, only the
 * {@code ERROR} log line and the {@code notification.undeliverable} metric
 * remain. Email only for now: the Slack channel attaches an ACK button bound
 * to the incident id and stores the message, which an operator alert must not
 * do.
 *
 * <p>A failure to send the alert is logged and swallowed: it must never turn a
 * handled undeliverable notification into a failed queue entry.
 *
 * <h2>Rate limit: one email per tenant and reason per interval</h2>
 * A tenant with nobody on call, or an oncall-service outage, makes every opened
 * and escalated incident undeliverable at once. One email per entry would bury
 * the operator's inbox with alerts a tenant can trigger at will, and would stall
 * the scheduler thread on SMTP. So the first alert for a (tenant, reason) is
 * emailed and logged at ERROR, and the following ones within
 * {@code notification.operator-alert.min-interval} (default PT15M) are only
 * logged at INFO; every entry is still counted in {@code notification.undeliverable}
 * and audited for the tenant. The memory is per replica and bounded, so it is best
 * effort. It fails closed: once it holds {@value #MAX_TRACKED_ALERTS} live pairs a
 * new pair is suppressed rather than emailed, because that is a flood. A failed
 * send holds the slot for only {@code FAILURE_RETRY_DELAY} (one minute), so an SMTP
 * outage does not silence the operator for the whole interval.
 */
@Service
public class OperatorAlertService {

    private static final Logger log =
            LoggerFactory.getLogger(OperatorAlertService.class);

    static final String EVENT_TYPE = "OPERATOR_ALERT";

    /** Upper bound on remembered (tenant, reason) pairs. */
    static final int MAX_TRACKED_ALERTS = 1_000;

    /** How long the slot is held after a failed send before the next alert may try again. */
    static final Duration FAILURE_RETRY_DELAY = Duration.ofMinutes(1);

    private final NotificationChannel emailChannel;
    private final String operatorEmail;
    private final Duration minInterval;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> lastAlertAt = new ConcurrentHashMap<>();

    @Autowired
    public OperatorAlertService(List<NotificationChannel> channels,
                                NotificationChannelProperties properties) {
        this(channels, properties, Clock.systemUTC());
    }

    OperatorAlertService(List<NotificationChannel> channels,
                         NotificationChannelProperties properties,
                         Clock clock) {
        this.minInterval = properties.operatorAlert().minInterval();
        this.clock = clock;
        this.emailChannel = channels.stream()
                .filter(ch -> NotificationChannels.EMAIL.equals(ch.channelName()))
                .findFirst()
                .orElse(null);
        this.operatorEmail = properties.operatorAlert().hasEmail()
                ? properties.operatorAlert().email() : null;
    }

    public void undeliverable(UUID incidentId, String tenantId,
                              String eventType, UndeliverableReason reason) {
        final String key = tenantId + "|" + reason;
        final Instant claimedAt = claimSlot(key);

        if (claimedAt == null) {
            // The router already logged this entry at WARN, and it is counted and
            // audited; an ERROR per repeat would let one misconfigured tenant page the
            // operator once per incident, which is what the limit is for.
            log.info("Operator alert suppressed — already alerted for this tenant " +
                            "and reason within {}: tenantId={}, reason={}",
                    minInterval, tenantId, reason);
            return;
        }

        log.error("Notification undeliverable — nobody in the tenant could be " +
                        "notified: incidentId={}, tenantId={}, eventType={}, reason={}",
                incidentId, tenantId, eventType, reason);

        if (operatorEmail == null) {
            log.error("No notification.operator-alert.email configured — the " +
                    "operator is not told by email");
            return;
        }
        if (emailChannel == null || !emailChannel.isEnabled()) {
            log.error("Email channel not available — the operator is not told " +
                    "by email");
            return;
        }

        try {
            emailChannel.send(new NotificationRequest(
                    incidentId,
                    tenantId,
                    EVENT_TYPE,
                    operatorEmail,
                    "[PLATFORM] Notification undeliverable (" + reason + ")",
                    String.format("Tenant: %s%nIncident: %s%nEvent: %s%nReason: %s%n"
                                    + "Nobody in the tenant could be notified. The "
                                    + "incident text was not sent anywhere.%n%n"
                                    + "This is a platform alert: the severity shown is not the "
                                    + "incident's. Further undeliverable notifications for this "
                                    + "tenant and reason are not emailed for %s; see the "
                                    + "notification.undeliverable metric and the audit events.",
                            tenantId, incidentId, eventType, reason, minInterval),
                    Severity.HIGH,
                    "Undeliverable notification"));
        } catch (Exception e) {
            // The slot is not held for the whole interval: the operator was not told,
            // so the next alert must be able to try again soon. But not immediately,
            // or during an SMTP outage every entry would block the scheduler on a
            // failing send.
            retryAfterFailure(key, claimedAt);
            log.error("Failed to send the operator alert: incidentId={}, " +
                            "tenantId={}, error={}",
                    incidentId, tenantId, e.getMessage(), e);
        }
    }

    /**
     * Takes the alert slot for this (tenant, reason), or returns null if it is
     * not free: an alert was already sent within the interval, or the map is full
     * of live keys. The second case fails closed: a flood of distinct (tenant,
     * reason) pairs is exactly when the limit is needed, and the log line, the
     * metric and the audit event still tell the operator. The scheduler is
     * single-threaded, so the check-then-put race between two threads does not
     * arise; if it ever did, the cost is one extra email.
     */
    private Instant claimSlot(String key) {
        final Instant now = clock.instant();
        final Instant last = lastAlertAt.get(key);

        if (last != null && Duration.between(last, now).compareTo(minInterval) < 0) {
            return null;
        }

        if (lastAlertAt.size() >= MAX_TRACKED_ALERTS) {
            lastAlertAt.values().removeIf(
                    t -> Duration.between(t, now).compareTo(minInterval) >= 0);
        }
        if (lastAlertAt.size() >= MAX_TRACKED_ALERTS && !lastAlertAt.containsKey(key)) {
            return null;
        }

        lastAlertAt.put(key, now);
        return now;
    }

    /**
     * After a failed send the slot is held only for {@link #FAILURE_RETRY_DELAY}
     * (or the interval, if that is shorter), not for the whole interval.
     */
    private void retryAfterFailure(String key, Instant claimedAt) {
        final Duration hold = FAILURE_RETRY_DELAY.compareTo(minInterval) < 0
                ? FAILURE_RETRY_DELAY : minInterval;
        lastAlertAt.replace(key, claimedAt, claimedAt.minus(minInterval).plus(hold));
    }
}
