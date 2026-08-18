package com.incidentplatform.oncall.dto;

import com.incidentplatform.oncall.validation.HasScheduleWindow;
import com.incidentplatform.oncall.validation.StartBeforeEnd;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * Request body for {@code POST /schedules/{id}/supersede} (backlog #43).
 *
 * <p>Deliberately the same shape as {@link CreateOncallScheduleRequest} —
 * a supersede is "create a new entry that replaces an old one," so it
 * needs the exact same fields with the exact same validation.
 *
 * <p>Kept as a separate type rather than reusing
 * {@code CreateOncallScheduleRequest} directly for a concrete reason, not
 * a speculative one: API contract clarity. {@code POST /schedules/{id}/supersede}
 * accepting a type literally named "Create...Request" would read as
 * misleading in generated API documentation (Swagger/OpenAPI) — implying
 * a new resource is being created, when the operation is an edit. This
 * mirrors why {@code IncidentEvent}'s five implementations
 * ({@code IncidentOpenedEvent}, {@code IncidentAcknowledgedEvent}, etc.)
 * are separate record types rather than one shared record with an
 * event-type field: distinct names for distinct operations, even when
 * (as here, today) the underlying shape happens to be identical.
 */
@StartBeforeEnd
public record UpdateOncallScheduleRequest(

        UUID teamId,

        @NotBlank(message = "userId is required")
        String userId,

        @NotBlank(message = "userName is required")
        String userName,

        @Email(message = "Valid email is required")
        @NotBlank(message = "email is required")
        String email,

        String phone,

        String slackUserId,

        @NotBlank(message = "role is required")
        @Pattern(
                regexp = "^(PRIMARY|SECONDARY|MANAGER)$",
                message = "role must be PRIMARY, SECONDARY or MANAGER"
        )
        String role,

        @NotNull(message = "startsAt is required")
        Instant startsAt,

        @NotNull(message = "endsAt is required")
        Instant endsAt,

        String notes
) implements HasScheduleWindow {}