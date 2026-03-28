package com.incidentplatform.ingestion.normalizer;

import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;

import java.util.List;

public record NormalizationResult(

        List<UnifiedAlertDto> firingAlerts,

        List<ResolvedAlertNotification> resolvedAlerts

) {
    public NormalizationResult {
        firingAlerts = firingAlerts != null
                ? List.copyOf(firingAlerts)
                : List.of();
        resolvedAlerts = resolvedAlerts != null
                ? List.copyOf(resolvedAlerts)
                : List.of();
    }

    public static NormalizationResult firingOnly(List<UnifiedAlertDto> alerts) {
        return new NormalizationResult(alerts, List.of());
    }

    public static NormalizationResult empty() {
        return new NormalizationResult(List.of(), List.of());
    }

    public boolean isEmpty() {
        return firingAlerts.isEmpty() && resolvedAlerts.isEmpty();
    }

    public int totalProcessed() {
        return firingAlerts.size() + resolvedAlerts.size();
    }
}