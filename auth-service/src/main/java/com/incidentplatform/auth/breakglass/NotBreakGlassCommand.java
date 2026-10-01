package com.incidentplatform.auth.breakglass;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches unless auth-service runs as the break-glass command
 * ({@link BreakGlassCommand}, backlog #0-88), whose one-off process must not
 * run scheduled jobs: it would take ShedLock locks that its exit could leave
 * held until they time out.
 */
public class NotBreakGlassCommand implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !BreakGlassCommand.isCommand(context.getEnvironment());
    }
}
