package com.incidentplatform.auth.breakglass;

import com.incidentplatform.auth.service.MfaService;
import com.incidentplatform.shared.audit.AuditOutboxRelay;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/**
 * The break-glass command's process contract (backlog #0-88): the exit code
 * it reports through {@code ExitCodeGenerator}, no reset at all when started
 * with a web server, and that Spring creates it only for the command.
 */
@DisplayName("BreakGlassMfaResetRunner")
class BreakGlassMfaResetRunnerTest {


    private final MfaService mfaService = mock(MfaService.class);
    private final AuditOutboxRelay relay = mock(AuditOutboxRelay.class);

    @SuppressWarnings("unchecked")
    private ObjectProvider<AuditOutboxRelay> relayProvider(AuditOutboxRelay relayOrNull) {
        final ObjectProvider<AuditOutboxRelay> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(relayOrNull);
        return provider;
    }

    private BreakGlassMfaResetRunner runner(ApplicationContext context) {
        return new BreakGlassMfaResetRunner(mfaService, relayProvider(relay), context,
                "ops@example.com", "Jane", "lost phone");
    }

    /** As SpringApplication does, so the runner's {@code @Value}s convert. */
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(
                    ApplicationConversionService.getSharedInstance()))
            .withBean(MfaService.class, () -> mfaService)
            .withUserConfiguration(BreakGlassMfaResetRunner.class);

    /** A context started as the command: the marker main adds for the subcommand, and the options. */
    private ApplicationContextRunner command() {
        return contexts.withInitializer(BreakGlassCommand::markAsCommand)
                .withPropertyValues("break-glass.mfa-reset.user-email=ops@example.com",
                "break-glass.mfa-reset.actor=Jane", "break-glass.mfa-reset.reason=lost phone");
    }

    @Test
    @DisplayName("reports 0 after a reset")
    void success() throws Exception {
        given(mfaService.resetMfaBreakGlass(eq("ops@example.com"), eq("Jane"), eq("lost phone"), argThat(origin -> origin != null && origin.contains("@"))))
                .willReturn(UUID.randomUUID());
        final BreakGlassMfaResetRunner runner = runner(mock(ApplicationContext.class));

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    @DisplayName("runs the reset in the operator tenant's TenantContext (log MDC, tenant-scoped calls), and clears it")
    void tenantContextForTheAudit() throws Exception {
        final AtomicReference<String> during = new AtomicReference<>();
        given(mfaService.resetMfaBreakGlass(eq("ops@example.com"), eq("Jane"), eq("lost phone"), argThat(origin -> origin != null && origin.contains("@")))).willAnswer(i -> {
            during.set(TenantContext.get());
            return UUID.randomUUID();
        });

        runner(mock(ApplicationContext.class)).run(new DefaultApplicationArguments());

        assertThat(during.get()).isEqualTo(ReservedTenants.PLATFORM_OPERATOR);
        assertThat(TenantContext.isSet()).as("cleared afterwards").isFalse();
    }

    @Test
    @DisplayName("reports 1 when the reset is refused or fails")
    void failure() throws Exception {
        willThrow(new IllegalStateException("database down"))
                .given(mfaService).resetMfaBreakGlass(eq("ops@example.com"), eq("Jane"), eq("lost phone"), argThat(origin -> origin != null && origin.contains("@")));
        final BreakGlassMfaResetRunner runner = runner(mock(ApplicationContext.class));

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    @DisplayName("reports 2 without resetting when a web server is running")
    void refusesWithWebServer() throws Exception {
        final BreakGlassMfaResetRunner runner = runner(mock(ServletWebServerApplicationContext.class));

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(2);
        then(mfaService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("reports failure until it has run, so an exit before the reset is never 0")
    void failureUntilRun() {
        assertThat(runner(mock(ApplicationContext.class)).getExitCode())
                .isEqualTo(BreakGlassMfaResetRunner.NOT_RUN).isNotZero();
    }

    @Test
    @DisplayName("Spring creates it, and main ends the process, only for the command")
    void onlyForTheCommand() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(BreakGlassMfaResetRunner.class);
            assertThat(BreakGlassMfaResetRunner.isCommand(context)).as("the service keeps running").isFalse();
        });
        command().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(BreakGlassMfaResetRunner.class);
            assertThat(BreakGlassMfaResetRunner.isCommand(context)).isTrue();
        });
    }

    @Test
    @DisplayName("SpringApplication.exit returns the command's code, as main uses it")
    void springApplicationExitCollectsTheCode() {
        given(mfaService.resetMfaBreakGlass(eq("ops@example.com"), eq("Jane"), eq("lost phone"), argThat(origin -> origin != null && origin.contains("@"))))
                .willReturn(UUID.randomUUID());
        // ApplicationContextRunner does not call ApplicationRunners; SpringApplication.run would.
        command().run(context -> {
            context.getBean(BreakGlassMfaResetRunner.class).run(new DefaultApplicationArguments());
            assertThat(SpringApplication.exit(context)).isZero();
        });

        willThrow(new IllegalArgumentException("break-glass reason is required"))
                .given(mfaService).resetMfaBreakGlass(eq("ops@example.com"), eq("Jane"), eq("lost phone"), argThat(origin -> origin != null && origin.contains("@")));
        command().run(context -> {
            context.getBean(BreakGlassMfaResetRunner.class).run(new DefaultApplicationArguments());
            assertThat(SpringApplication.exit(context)).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("the options alone, as arguments or variables, start no command and keep scheduling on (review of #0-88)")
    void propertiesAloneStartNothing() {
        contexts.withPropertyValues("break-glass.mfa-reset.user-email=ops@example.com",
                        "break-glass.mfa-reset.actor=Jane", "break-glass.mfa-reset.reason=lost phone")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(BreakGlassMfaResetRunner.class);
                    assertThat(BreakGlassMfaResetRunner.isCommand(context)).isFalse();
                });
        // A property named like the marker (as SPRING_APPLICATION_JSON or a variable could set) is no marker.
        contexts.withPropertyValues("breakGlassCommand=true")
                .run(context -> assertThat(context).doesNotHaveBean(BreakGlassMfaResetRunner.class));
        final MockEnvironment variables = new MockEnvironment()
                .withProperty("break-glass.mfa-reset.user-email", "ops@example.com")
                .withProperty("breakGlassCommand", "true");
        assertThat(new NotBreakGlassCommand().matches(conditionContext(variables), null)).isTrue();
    }

    @Test
    @DisplayName("only the subcommand as the first argument prepares the command: no web server, marker, args without it")
    void prepareOnlyForTheSubcommand() {
        final SpringApplication plain = new SpringApplication(Object.class);
        final String[] serviceArgs = {"--server.port=8087", "break-glass-mfa-reset"};
        assertThat(BreakGlassCommand.prepare(plain, serviceArgs)).isSameAs(serviceArgs);
        assertThat(plain.getInitializers()).noneMatch(i -> i.getClass().getName().contains("BreakGlassCommand"));
        assertThat(BreakGlassCommand.prepare(plain, new String[0])).isEmpty();

        final SpringApplication command = new SpringApplication(Object.class);
        final int initializersBefore = command.getInitializers().size();
        final String[] springArgs = BreakGlassCommand.prepare(command, new String[] {
                "break-glass-mfa-reset", "--break-glass.mfa-reset.user-email=ops@example.com"});

        assertThat(springArgs).containsExactly("--break-glass.mfa-reset.user-email=ops@example.com");
        assertThat(command.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
        assertThat(command.getInitializers()).hasSize(initializersBefore + 1);

        // The initializer prepare registered is the one that marks the context (review of #0-88).
        final GenericApplicationContext context = new GenericApplicationContext();
        applyInitializers(command, context);
        assertThat(BreakGlassCommand.isCommand(context.getEnvironment())).isTrue();
        final GenericApplicationContext service = new GenericApplicationContext();
        applyInitializers(plain, service);
        assertThat(BreakGlassCommand.isCommand(service.getEnvironment())).isFalse();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyInitializers(SpringApplication application, ConfigurableApplicationContext context) {
        for (final ApplicationContextInitializer initializer : application.getInitializers()) {
            if (initializer.getClass().getName().contains("BreakGlassCommand")) {
                initializer.initialize(context);
            }
        }
    }

    @Test
    @DisplayName("executedOn is user@host of this process")
    void executedOnNamesUserAndHost() {
        assertThat(BreakGlassMfaResetRunner.executedOn())
                .startsWith(System.getProperty("user.name") + "@").doesNotEndWith("@");
    }

    @Test
    @DisplayName("an unknown user or host is named as such, never blank, and the result fits the service's limit")
    void executedOnFallbacksAndLength() {
        assertThat(BreakGlassMfaResetRunner.formatExecutedOn("ops", "host-1")).isEqualTo("ops@host-1");
        assertThat(BreakGlassMfaResetRunner.formatExecutedOn(null, "host-1")).isEqualTo("unknown-user@host-1");
        assertThat(BreakGlassMfaResetRunner.formatExecutedOn(" ", null)).isEqualTo("unknown-user@unknown-host");
        assertThat(BreakGlassMfaResetRunner.formatExecutedOn("ops", " ")).isEqualTo("ops@unknown-host");

        final String longHost = "a".repeat(253);
        final String cut = BreakGlassMfaResetRunner.formatExecutedOn("ops", longHost);
        assertThat(cut).startsWith("ops@a").hasSize(MfaService.BREAK_GLASS_EXECUTED_ON_MAX);
    }

    @Test
    @DisplayName("runner and scheduling use one rule, the marker: the command turns scheduling off, nothing else does")
    void oneRuleForBoth() {
        final MockEnvironment none = new MockEnvironment();
        final GenericApplicationContext marked =
                new GenericApplicationContext();
        BreakGlassCommand.markAsCommand(marked);

        assertThat(new BreakGlassCommand().matches(conditionContext(none), null)).isFalse();
        assertThat(new NotBreakGlassCommand().matches(conditionContext(none), null)).isTrue();
        final ConditionContext command = mock(ConditionContext.class);
        given(command.getEnvironment()).willReturn(marked.getEnvironment());
        assertThat(new BreakGlassCommand().matches(command, null)).isTrue();
        assertThat(new NotBreakGlassCommand().matches(command, null)).isFalse();
    }

    @Test
    @DisplayName("reports 1 without resetting when the user email is missing")
    void missingEmail() throws Exception {
        final BreakGlassMfaResetRunner runner = new BreakGlassMfaResetRunner(
                mfaService, relayProvider(relay), mock(ApplicationContext.class), " ", "Jane", "lost phone");

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
        then(mfaService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("after the reset it sends the outbox's audit event at once (backlog #0-84)")
    void sendsAuditEventAfterReset() throws Exception {
        given(mfaService.resetMfaBreakGlass(eq("ops@example.com"), eq("Jane"), eq("lost phone"), anyString()))
                .willReturn(UUID.randomUUID());
        given(relay.relayNow()).willReturn(new AuditOutboxRelay.RunResult(1, 0));
        final BreakGlassMfaResetRunner runner = runner(mock(ApplicationContext.class));

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isZero();
        final InOrder order = inOrder(mfaService, relay);
        order.verify(mfaService).resetMfaBreakGlass(eq("ops@example.com"), eq("Jane"), eq("lost phone"), anyString());
        order.verify(relay).relayNow();
    }

    @Test
    @DisplayName("Kafka unreachable after the reset: still 0, the event waits in the outbox for the service")
    void auditEventLeftForService() throws Exception {
        given(mfaService.resetMfaBreakGlass(eq("ops@example.com"), eq("Jane"), eq("lost phone"), anyString()))
                .willReturn(UUID.randomUUID());
        final BreakGlassMfaResetRunner failing = runner(mock(ApplicationContext.class));
        given(relay.relayNow()).willReturn(new AuditOutboxRelay.RunResult(0, 1));
        failing.run(new DefaultApplicationArguments());
        assertThat(failing.getExitCode()).isZero();

        final BreakGlassMfaResetRunner throwing = runner(mock(ApplicationContext.class));
        willThrow(new IllegalStateException("db")).given(relay).relayNow();
        throwing.run(new DefaultApplicationArguments());
        assertThat(throwing.getExitCode()).isZero();

        final BreakGlassMfaResetRunner withoutOutbox = new BreakGlassMfaResetRunner(mfaService,
                relayProvider(null), mock(ApplicationContext.class), "ops@example.com", "Jane", "lost phone");
        withoutOutbox.run(new DefaultApplicationArguments());
        assertThat(withoutOutbox.getExitCode()).isZero();
    }

    @Test
    @DisplayName("a refused reset sends nothing")
    void refusedResetSendsNothing() throws Exception {
        willThrow(new IllegalArgumentException("no such operator"))
                .given(mfaService).resetMfaBreakGlass(eq("ops@example.com"), eq("Jane"), eq("lost phone"), anyString());
        final BreakGlassMfaResetRunner runner = runner(mock(ApplicationContext.class));

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
        then(relay).shouldHaveNoInteractions();
    }

    private static ConditionContext conditionContext(MockEnvironment environment) {
        final ConditionContext context = mock(ConditionContext.class);
        given(context.getEnvironment()).willReturn(environment);
        return context;
    }
}
