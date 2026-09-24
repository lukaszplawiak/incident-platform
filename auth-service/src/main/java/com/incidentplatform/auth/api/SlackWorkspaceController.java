package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.InstallSlackWorkspaceRequest;
import com.incidentplatform.auth.dto.SlackWorkspaceDto;
import com.incidentplatform.auth.dto.SlackWorkspaceInternalDto;
import com.incidentplatform.auth.service.SlackWorkspaceService;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Per-tenant Slack workspace connections (backlog #0-21).
 *
 * <p>The three admin endpoints under {@code /api/v1/slack-workspace} are the
 * tenant-facing management surface (install/view/revoke), {@code hasRole('ADMIN')}
 * like {@link IntegrationController}'s endpoints, and never return the bot
 * token after install.
 *
 * <p>{@code GET /api/v1/internal/slack-workspace} is the one narrow exception
 * to auth-service never being called by another service (backlog #0-30) —
 * {@code hasRole('SERVICE')} only, accepted solely from a token whose
 * {@code aud} claim names auth-service. It must not dereference
 * {@code @AuthenticationPrincipal UserPrincipal}, which Spring leaves
 * {@code null} for a service caller (see {@code ServicePrincipal}'s own
 * Javadoc) — the tenant is read from {@link
 * com.incidentplatform.shared.security.TenantContext} inside the service
 * layer instead, exactly like every admin endpoint here already does.
 */
@RestController
@Tag(name = "Slack Workspace",
        description = "Per-tenant Slack workspace connections used for incident notifications.")
public class SlackWorkspaceController {

    private final SlackWorkspaceService slackWorkspaceService;

    public SlackWorkspaceController(SlackWorkspaceService slackWorkspaceService) {
        this.slackWorkspaceService = slackWorkspaceService;
    }

    @PostMapping(
            value = "/api/v1/slack-workspace",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Install this tenant's Slack workspace connection",
            description = """
                    Connects this tenant's own Slack workspace: create a Slack App in
                    your workspace, generate a bot token (starts with xoxb-), and paste
                    it here. One active workspace per tenant — revoke the existing one
                    first to replace it. The bot token is encrypted at rest and never
                    returned again after this call. Slack messages carry no Acknowledge
                    button with a token from your own App; acknowledge incidents in the app.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Slack workspace installed"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "Team not found"),
            @ApiResponse(responseCode = "409", description = "An active workspace already exists")
    })
    public ResponseEntity<SlackWorkspaceDto> install(
            @Valid @RequestBody InstallSlackWorkspaceRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(slackWorkspaceService.install(request, principal));
    }

    @GetMapping(value = "/api/v1/slack-workspace", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "View this tenant's Slack workspace connection",
            description = "Never returns the bot token — metadata only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Slack workspace found"),
            @ApiResponse(responseCode = "404", description = "No active Slack workspace for this tenant")
    })
    public ResponseEntity<SlackWorkspaceDto> get() {
        return ResponseEntity.ok(slackWorkspaceService.get());
    }

    @DeleteMapping("/api/v1/slack-workspace/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Revoke this tenant's Slack workspace connection",
            description = "Notifications stop posting to Slack for this tenant immediately. " +
                    "The workspace and its (encrypted) token are preserved in the database for audit.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Slack workspace revoked"),
            @ApiResponse(responseCode = "404", description = "Slack workspace not found"),
            @ApiResponse(responseCode = "409", description = "Slack workspace already revoked")
    })
    public ResponseEntity<Void> revoke(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        slackWorkspaceService.revoke(id, principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/api/v1/internal/slack-workspace", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('SERVICE')")
    @Operation(
            summary = "[Internal] Read this tenant's Slack workspace connection, decrypted",
            description = "Service-to-service only (backlog #0-30). Called by notification-service " +
                    "to send Slack notifications with the tenant's own bot token.",
            hidden = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Slack workspace found"),
            @ApiResponse(responseCode = "403", description = "ROLE_SERVICE with aud=auth-service required"),
            @ApiResponse(responseCode = "404", description = "No active Slack workspace for this tenant")
    })
    public ResponseEntity<SlackWorkspaceInternalDto> getForServiceRead() {
        return slackWorkspaceService.getForServiceRead()
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Slack workspace", "active"));
    }
}
