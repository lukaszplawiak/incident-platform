package com.incidentplatform.ingestion.normalizer;

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
 */
public record NormalizationResult(

        List<UnifiedAlertDto> firingAlerts,

        List<ResolvedAlertNotification> resolvedAlerts,

        int totalReceived

) {
    public NormalizationResult {
        firingAlerts = firingAlerts != null
                ? List.copyOf(firingAlerts)
                : List.of();
        resolvedAlerts = resolvedAlerts != null
                ? List.copyOf(resolvedAlerts)
                : List.of();
    }

    /**
     * Convenience for normalizers that don't batch/truncate (currently
     * every normalizer except {@link PrometheusNormalizer} — Generic and
     * Wazuh each normalize exactly one alert per call). {@code
     * totalReceived} is simply {@code alerts.size()} — there's nothing to
     * have been truncated.
     */
    public static NormalizationResult firingOnly(List<UnifiedAlertDto> alerts) {
        final int size = alerts != null ? alerts.size() : 0;
        return new NormalizationResult(alerts, List.of(), size);
    }

    public static NormalizationResult empty() {
        return new NormalizationResult(List.of(), List.of(), 0);
    }

    public boolean isEmpty() {
        return firingAlerts.isEmpty() && resolvedAlerts.isEmpty();
    }

    public int totalProcessed() {
        return firingAlerts.size() + resolvedAlerts.size();
    }

    /**
     * True when {@link #totalReceived} exceeds what actually made it into
     * {@link #firingAlerts}/{@link #resolvedAlerts} — i.e. a normalizer
     * (currently only {@link PrometheusNormalizer}, via
     * {@code ingestion.prometheus.max-batch-size}) capped the batch and
     * silently dropped the remainder before this result was even built.
     */
    public boolean isTruncated() {
        return totalReceived > totalProcessed();
    }
}