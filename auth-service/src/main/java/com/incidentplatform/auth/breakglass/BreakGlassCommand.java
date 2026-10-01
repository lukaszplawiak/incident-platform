package com.incidentplatform.auth.breakglass;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Arrays;
import java.util.Map;

/**
 * auth-service as the break-glass command (backlog #0-88): chosen only by the
 * subcommand {@value #SUBCOMMAND} as the first argument, the way Django's
 * {@code manage.py <command>} or Keycloak's {@code kc.sh <subcommand>} pick a
 * mode.
 *
 * <pre>
 * java -jar app.jar break-glass-mfa-reset --break-glass.mfa-reset.user-email=... ...
 * </pre>
 *
 * <h2>Why a subcommand, not a property</h2>
 * The first version started the command when the property
 * {@code break-glass.mfa-reset.user-email} was set, from an argument or an
 * environment variable. A variable left in a deployment by mistake would then
 * have turned the running service into the command (found in review). An
 * argument in first position is something only whoever starts the process
 * gives; no environment variable or {@code --property} can supply it.
 *
 * <p>{@link #prepare} recognises the subcommand before Spring starts, starts
 * the application without a web server, and marks the environment with a
 * property source of its own name ({@value #MARKER_SOURCE}); this condition,
 * used by {@link BreakGlassMfaResetRunner} and, negated, by
 * {@link NotBreakGlassCommand} for scheduling, checks only that marker, so
 * the runner and scheduling can never disagree.
 */
public class BreakGlassCommand implements Condition {

    public static final String SUBCOMMAND = "break-glass-mfa-reset";

    static final String MARKER_SOURCE = "breakGlassCommand";

    /**
     * Prepares {@code application} for the command when {@code args} start
     * with {@value #SUBCOMMAND}: no web server, the marker added, and the
     * subcommand removed from the arguments Spring sees.
     *
     * @return the arguments to start Spring with
     */
    public static String[] prepare(SpringApplication application, String[] args) {
        if (args.length == 0 || !SUBCOMMAND.equals(args[0])) {
            return args;
        }
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(BreakGlassCommand::markAsCommand);
        return Arrays.copyOfRange(args, 1, args.length);
    }

    /** Adds the marker; {@link #prepare} does it for the real command, tests for their contexts. */
    static void markAsCommand(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(MARKER_SOURCE, Map.of(MARKER_SOURCE, true)));
    }

    static boolean isCommand(Environment environment) {
        return environment instanceof ConfigurableEnvironment configurable
                && configurable.getPropertySources().contains(MARKER_SOURCE);
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isCommand(context.getEnvironment());
    }
}
