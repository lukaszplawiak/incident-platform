package com.incidentplatform.auth.breakglass;

import org.springframework.context.ConfigurableApplicationContext;

/** Lets tests in other packages start a context as the break-glass command (backlog #0-88). */
public final class BreakGlassCommandTestSupport {

    private BreakGlassCommandTestSupport() {
    }

    public static void markAsCommand(ConfigurableApplicationContext context) {
        BreakGlassCommand.markAsCommand(context);
    }
}
