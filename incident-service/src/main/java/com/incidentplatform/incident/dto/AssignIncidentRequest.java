package com.incidentplatform.incident.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code PATCH /api/v1/incidents/{id}/assignee}.
 *
 * <h2>Why a dedicated DTO and not a path variable</h2>
 * The previous design placed {@code userId} in the URL path:
 * {@code PUT /api/v1/incidents/{id}/assign/{userId}}. This had two problems:
 * <ol>
 *   <li><b>Wrong HTTP method</b> — {@code PUT} semantically replaces the
 *       entire resource. Assigning a user modifies only one field of an
 *       {@code Incident}, which is a partial update: {@code PATCH}.</li>
 *   <li><b>Non-standard URL design</b> — {@code assign} is a verb; REST
 *       resource naming prefers nouns. Embedding the target user ID in the
 *       path leaves no room to pass additional data (e.g. a comment)
 *       without another breaking URL change, and prevents {@code @Valid}
 *       bean validation from running on the input.</li>
 * </ol>
 *
 * <h2>New design</h2>
 * {@code PATCH /api/v1/incidents/{id}/assignee} with this record as the
 * request body mirrors the existing {@code PATCH /api/v1/incidents/{id}/status}
 * + {@link UpdateStatusCommand} pattern — both are partial updates on a
 * named sub-resource (noun), both carry their payload in a validated body.
 * The sub-resource name {@code assignee} is a noun describing the person
 * currently assigned, consistent with GitHub's Issues API
 * ({@code PUT /repos/.../issues/{number}/assignees}) and PagerDuty's
 * Incidents API.
 *
 * <h2>Extensibility</h2>
 * Moving the payload to a body means a future {@code comment} field can be
 * added without any URL change — a non-breaking addition to this record.
 */
public record AssignIncidentRequest(

        @NotNull(message = "userId is required")
        @JsonProperty("userId")
        UUID userId

) {}