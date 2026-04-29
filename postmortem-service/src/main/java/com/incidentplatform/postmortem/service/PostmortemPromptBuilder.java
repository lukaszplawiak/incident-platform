package com.incidentplatform.postmortem.service;

import com.incidentplatform.postmortem.domain.Postmortem;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PostmortemPromptBuilder {

    // In the future, this can be injected from application.yml or a database
    // to allow per-tenant prompt configuration.
    private static final String PROMPT_TEMPLATE = """
            You are an experienced SRE (Site Reliability Engineer) writing a postmortem document.

            Write a professional postmortem for the following incident:

            Title: %s
            Severity: %s
            Duration: %d minutes
            Started: %s
            Resolved: %s

            The postmortem should include the following sections:

            ## Summary
            A brief 2-3 sentence description of what happened and the impact.

            ## Timeline
            A chronological list of key events during the incident.

            ## Root Cause
            The technical root cause of the incident.

            ## Impact
            Who was affected and how.

            ## Resolution
            What was done to resolve the incident.

            ## Action Items
            3-5 concrete action items to prevent recurrence, each with a suggested owner role \
            (e.g., Backend Team, SRE Team).

            ## Lessons Learned
            Key takeaways from this incident.

            Write in a professional, factual tone. Use markdown formatting.
            Keep each section concise and actionable.
            """;

    public String build(Postmortem postmortem) {
        return build(
                postmortem.getIncidentTitle(),
                postmortem.getIncidentSeverity(),
                postmortem.getDurationMinutes(),
                postmortem.getIncidentOpenedAt(),
                postmortem.getIncidentResolvedAt()
        );
    }

    public String build(String title,
                        String severity,
                        int durationMinutes,
                        Instant openedAt,
                        Instant resolvedAt) {
        return String.format(
                PROMPT_TEMPLATE,
                title,
                severity,
                durationMinutes,
                openedAt.toString(),
                resolvedAt.toString()
        );
    }
}