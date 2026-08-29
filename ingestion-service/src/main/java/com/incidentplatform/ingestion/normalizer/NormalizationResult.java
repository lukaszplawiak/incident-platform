package com.incidentplatform.ingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;

import java.util.List;

/**
 * <h2>Fixed: silent truncation with no signal to the caller</h2>
 * {@code totalReceived} was added because {@link PrometheusNormalizer}
 * caps how many alerts it processes per batch
 * ({@code ingestion.prometheus.max-batch-size}) — when a payload exceeds
 * that limit, it previously only logged a WARN and silently dropped the
 * rest. {@code AlertIngestionService} computed
 * {@code IngestionSummary.received} as
 * {@code firingAlerts.size() + resolvedAlerts.size()} — i.e. from
 * whatever this record already held, which was already the
 * <em>truncated</em> count by the time it got here. A caller (e.g.
 * Alertmanager) sending 1000 alerts against a 500-alert limit received
 * back {@code received: 500} with nothing distinguishing that from "the
 * payload only had 500 alerts to begin with" — 500 real alerts silently
 * vanished with zero visibility, potentially hiding real incidents from
 * ever reaching the platform.
 *
 * <p>{@code totalReceived} carries the true, pre-truncation count
 * (equal to {@code firingAlerts.size() + resolvedAlerts.size()} in the
 * normal, non-truncated case — see {@link #firingOnly}) so
 * {@code AlertIngestionService} can report an honest
 * {@code IngestionSummary.received} and a {@code truncated} flag instead
 * of silently reporting the smaller, already-capped number as if it were
 * the whole truth.
 *
 * <h2>Fixed (backlog #69): one malformed alert no longer fails the
 * whole batch</h2>
 * {@link PrometheusNormalizer} previously let a
 * {@link NormalizationException} from any single alert within a batch
 * propagate uncaught, which {@code AlertIngestionService} then handled
 * by dead-lettering the <em>entire</em> payload as one unit — including
 * every other alert in that batch, however many, however valid. Given
 * real Alertmanager traffic groups many alerts into one webhook call,
 * and batches are largest during exactly the major incidents where
 * alert delivery matters most, this meant a single malformed alert
 * (from a misconfigured rule, a non-Alertmanager caller mimicking the
 * format, or a mangled entry from an intermediate proxy) could silently
 * withhold many genuinely valid, actionable alerts until someone
 * noticed the dead-letter entry and manually recovered them.
 *
 * <p>{@link #malformedAlerts} now carries each alert that individually
 * failed normalization, isolated from the ones that succeeded — see
 * {@code PrometheusNormalizer}'s own per-item try/catch for where these
 * are collected, and {@code AlertIngestionService} for where each one is
 * now dead-lettered individually (the same
 * {@code DeadLetterPublisher.publish} call already used for
 * serialization failures, just also reached from this new source), while
 * every other alert in the same batch is still processed normally. Same
 * principle already established elsewhere in this codebase for batch
 * processing — see {@code AuthEmailScheduler}'s (auth-service)
 * {@code continuesAfterOneFailure} test: one bad item in a batch must not
 * stop the rest from being handled.
 *
 * <p>{@link #totalProcessed()} now includes {@code malformedAlerts.size()}
 * alongside firing and resolved counts — deliberately, so
 * {@link #isTruncated()} keeps meaning specifically "the batch-size limit
 * dropped alerts before they were even attempted," not "something,
 * anything, didn't end up in firingAlerts/resolvedAlerts." A batch with
 * some malformed alerts but no batch-size truncation should not report
 * itself as truncated.
 */
public record NormalizationResult(

        List<UnifiedAlertDto> firingAlerts,

        List<ResolvedAlertNotification> resolvedAlerts,

        List<MalformedAlert> malformedAlerts,

        int totalReceived

) {
    /**
     * One alert that was individually attempted and failed normalization
     * within an otherwise-processed batch (backlog #69) — carries enough
     * to dead-letter it on its own: the raw sub-payload exactly as
     * received, and why it failed.
     */
    public record MalformedAlert(JsonNode rawAlert, String reason) {}

    public NormalizationResult {
        firingAlerts = firingAlerts != null
                ? List.copyOf(firingAlerts)
                : List.of();
        resolvedAlerts = resolvedAlerts != null
                ? List.copyOf(resolvedAlerts)
                : List.of();
        malformedAlerts = malformedAlerts != null
                ? List.copyOf(malformedAlerts)
                : List.of();
    }

    /**
     * Convenience for normalizers that don't batch/truncate (currently
     * every normalizer except {@link PrometheusNormalizer} — Generic and
     * Wazuh each normalize exactly one alert per call). {@code
     * totalReceived} is simply {@code alerts.size()} — there's nothing to
     * have been truncated, and nothing to have been individually
     * malformed either: a normalization failure for a single-alert
     * normalizer throws in the ordinary way, since there is no "rest of
     * the batch" to isolate it from.
     */
    public static NormalizationResult firingOnly(List<UnifiedAlertDto> alerts) {
        final int size = alerts != null ? alerts.size() : 0;
        return new NormalizationResult(alerts, List.of(), List.of(), size);
    }

    public static NormalizationResult empty() {
        return new NormalizationResult(List.of(), List.of(), List.of(), 0);
    }

    public boolean isEmpty() {
        return firingAlerts.isEmpty() && resolvedAlerts.isEmpty();
    }

    public int totalProcessed() {
        return firingAlerts.size() + resolvedAlerts.size() + malformedAlerts.size();
    }

    /**
     * True when {@link #totalReceived} exceeds what actually made it into
     * {@link #firingAlerts}/{@link #resolvedAlerts}/{@link #malformedAlerts}
     * combined — i.e. a normalizer (currently only
     * {@link PrometheusNormalizer}, via
     * {@code ingestion.prometheus.max-batch-size}) capped the batch and
     * silently dropped the remainder before this result was even built.
     * Deliberately unaffected by {@link #malformedAlerts} — see this
     * class's own Javadoc (backlog #69) for why those are counted inside
     * {@link #totalProcessed()} instead.
     */
    public boolean isTruncated() {
        return totalReceived > totalProcessed();
    }
}