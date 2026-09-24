package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/slack-workspace} (backlog #0-21).
 *
 * <p>Manual bot-token entry, not an OAuth install flow: the tenant admin
 * creates their own Slack App in their own workspace, generates a bot
 * token, and pastes it here — the same level of ceremony as today's single
 * global token, and the same flow {@link CreateIntegrationRequest} already
 * uses for tenant-supplied credentials.
 *
 * <p>Consequence (backlog #0-35): Slack signs interactive callbacks with the
 * signing secret of the App that sent the message, and each tenant's App has
 * its own. notification-service verifies callbacks with one platform-wide
 * secret, so a tenant's clicks could never be verified. Messages therefore
 * carry no Acknowledge button; incidents are acknowledged in the app. The
 * OAuth "Add to Slack" install (one platform App for every workspace) brings
 * the button back without changing this entity.
 */
public record InstallSlackWorkspaceRequest(

        @NotBlank(message = "slackTeamId must not be blank")
        @Size(max = 255, message = "slackTeamId must not exceed 255 characters")
        String slackTeamId,

        @NotBlank(message = "botToken must not be blank")
        @Pattern(regexp = "^xoxb-.+",
                message = "botToken must be a Slack bot token (starts with xoxb-)")
        String botToken,

        /**
         * Optional team scoping for a future team-scoped channel — not
         * enforced in routing yet.
         */
        UUID teamId,

        @Size(max = 255, message = "defaultChannel must not exceed 255 characters")
        String defaultChannel,

        /**
         * When true, every notification is also posted to
         * {@link #defaultChannel} in addition to (or instead of, if the
         * recipient has no Slack user id) a direct message. Defaults to
         * false — see backlog #0-18 for why a shared-channel broadcast is
         * opt-in.
         */
        Boolean broadcastEnabled

) {}
