package com.incidentplatform.incident.service;

import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.repository.IncidentRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.SourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IncidentCommandService#recordEscalationLevel} on a real Postgres
 * (backlog #0-96, found in review): its transaction commits, and a lost
 * {@code @Version} race is thrown, before it returns — which is what lets
 * {@code IncidentEscalationEventConsumer} acknowledge only a committed change
 * and nack a conflict. Its listener used to be {@code @Transactional} itself,
 * so the conflict surfaced at the commit, after the listener's {@code catch}
 * and after its acknowledgement. The query is tenant-scoped.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(IncidentCommandService.class)
// Every call runs in the service's own transaction, as in production; the
// test's default transaction would make the service's join it.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("recordEscalationLevel (Postgres, backlog #0-96)")
class IncidentEscalationLevelIntegrationTest {

    /** Its own, narrow configuration (see AuditPersistenceIntegrationTest). */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Incident.class)
    @EnableJpaRepositories(basePackageClasses = IncidentRepository.class)
    static class TestConfig {

        /**
         * Runs {@link #afterRead} after the repository's tenant-scoped read: a
         * Spring Data repository is an interface proxy, which a Mockito spy
         * cannot call through, so the hook wraps it instead.
         */
        @Bean
        static BeanPostProcessor afterReadHook() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof IncidentRepository repository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(IncidentRepository.class.getClassLoader(),
                            new Class<?>[]{IncidentRepository.class}, (proxy, method, args) -> {
                                final Object result;
                                try {
                                    result = method.invoke(repository, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                                if (method.getName().equals("findByIdAndTenantId")) {
                                    afterRead.accept((UUID) args[0]);
                                }
                                return result;
                            });
                }
            };
        }
    }

    /** What happens right after the service read the incident; nothing by default. */
    private static volatile Consumer<UUID> afterRead = id -> { };

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private IncidentCommandService commandService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IncidentRepository incidentRepository;
    @MockitoBean private IncidentEventPublisher eventPublisher;
    @MockitoBean private IncidentWebSocketPublisher webSocketPublisher;
    @MockitoBean private AuditEventPublisher auditEventPublisher;
    @MockitoBean private IncidentCreationService incidentCreationService;

    @AfterEach
    void noHook() {
        afterRead = id -> { };
    }

    private Incident stored(String tenantId) {
        return incidentRepository.saveAndFlush(new Incident(
                tenantId, "High CPU usage", "CPU exceeded 95%", Severity.HIGH, SourceType.OPS, "prometheus",
                "prometheus:highcpu:" + UUID.randomUUID(), UUID.randomUUID(), Instant.now()));
    }

    private int levelOf(UUID incidentId) {
        return jdbcTemplate.queryForObject("SELECT escalation_level FROM incidents WHERE id = ?", Integer.class,
                incidentId);
    }

    @Test
    @DisplayName("records the level and commits it before returning")
    void commitsBeforeReturning() {
        final Incident incident = stored("acme");

        assertThat(commandService.recordEscalationLevel(incident.getId(), "acme", 2)).isTrue();

        assertThat(levelOf(incident.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("a REST change committed between its read and its commit is thrown from the call itself")
    void versionConflictThrownFromTheCall() {
        final Incident incident = stored("acme");
        // After the service has read the row, another transaction (another
        // thread, its own connection) changes it, as an ACK through the API would.
        afterRead = id -> CompletableFuture.runAsync(() -> jdbcTemplate.update(
                "UPDATE incidents SET version = version + 1, status = 'ACKNOWLEDGED' WHERE id = ?", id)).join();

        assertThatThrownBy(() -> commandService.recordEscalationLevel(incident.getId(), "acme", 2))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(levelOf(incident.getId())).as("the conflicting write was rolled back").isZero();
    }


    @Test
    @DisplayName("another tenant's incident is not found and not touched")
    void otherTenant() {
        final Incident globex = stored("globex");

        assertThat(commandService.recordEscalationLevel(globex.getId(), "acme", 2)).isFalse();

        assertThat(levelOf(globex.getId())).isZero();
    }
}
