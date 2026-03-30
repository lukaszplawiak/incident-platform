package com.incidentplatform.incident.domain;

import com.incidentplatform.shared.exception.BusinessException;

import java.util.Map;
import java.util.Set;

public final class IncidentFsm {

    private static final Map<IncidentStatus, Set<IncidentStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    IncidentStatus.OPEN, Set.of(
                            IncidentStatus.ACKNOWLEDGED,
                            IncidentStatus.ESCALATED
                    ),
                    IncidentStatus.ACKNOWLEDGED, Set.of(
                            IncidentStatus.RESOLVED
                    ),
                    IncidentStatus.ESCALATED, Set.of(
                            IncidentStatus.ACKNOWLEDGED
                    ),
                    IncidentStatus.RESOLVED, Set.of(
                            IncidentStatus.CLOSED
                    ),
                    IncidentStatus.CLOSED, Set.of()
            );

    private IncidentFsm() {
        throw new UnsupportedOperationException("IncidentFsm is a utility class");
    }

    public static void validateTransition(IncidentStatus currentStatus,
                                          IncidentStatus targetStatus) {
        final Set<IncidentStatus> allowedTargets =
                ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of());

        if (!allowedTargets.contains(targetStatus)) {
            throw BusinessException.invalidStatusTransition(
                    currentStatus.name(), targetStatus.name());
        }
    }

    public static boolean isTransitionAllowed(IncidentStatus currentStatus,
                                              IncidentStatus targetStatus) {
        return ALLOWED_TRANSITIONS
                .getOrDefault(currentStatus, Set.of())
                .contains(targetStatus);
    }

    public static Set<IncidentStatus> getAllowedTransitions(
            IncidentStatus currentStatus) {
        return ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of());
    }

    public static boolean isTerminalState(IncidentStatus status) {
        return ALLOWED_TRANSITIONS
                .getOrDefault(status, Set.of())
                .isEmpty();
    }
}