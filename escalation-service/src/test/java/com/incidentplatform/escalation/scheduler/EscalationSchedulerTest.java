package com.incidentplatform.escalation.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("EscalationScheduler")
class EscalationSchedulerTest {

    @Mock
    private EscalationTaskRepository taskRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private EscalationScheduler scheduler;

    private static final String TOPIC = "incidents.lifecycle";
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        final ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        scheduler = new EscalationScheduler(
                taskRepository, kafkaTemplate, objectMapper);
        ReflectionTestUtils.setField(scheduler,
                "incidentsLifecycleTopic", TOPIC);
    }

    @Nested
    @DisplayName("checkAndEscalate")
    class CheckAndEscalate {

        @Test
        @DisplayName("should escalate overdue PENDING tasks")
        void shouldEscalateOverdueTasks() {
            // given
            final EscalationTask task = buildOverdueTask();
            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of(task));
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));
            given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .willReturn(null);

            // when
            scheduler.checkAndEscalate();

            // then
            then(kafkaTemplate).should()
                    .send(eq(TOPIC), eq(TENANT_ID), anyString());

            // then
            then(taskRepository).should().save(task);
        }

        @Test
        @DisplayName("should mark task as ESCALATED after sending event")
        void shouldMarkTaskAsEscalated() {
            // given
            final EscalationTask task = buildOverdueTask();
            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of(task));
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));
            given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .willReturn(null);

            // when
            scheduler.checkAndEscalate();

            // then
            org.assertj.core.api.Assertions.assertThat(task.getStatus())
                    .isEqualTo("ESCALATED");
        }

        @Test
        @DisplayName("should do nothing when no tasks are due")
        void shouldDoNothingWhenNoTasksDue() {
            // given
            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of());

            // when
            scheduler.checkAndEscalate();

            // then
            then(kafkaTemplate).should(never())
                    .send(anyString(), anyString(), anyString());
            then(taskRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("should continue escalating other tasks if one fails")
        void shouldContinueAfterOneFailure() {
            // given
            final EscalationTask task1 = buildOverdueTask();
            final EscalationTask task2 = buildOverdueTask();

            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of(task1, task2));
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .willThrow(new RuntimeException("Kafka unavailable"))
                    .willReturn(null);

            // when
            scheduler.checkAndEscalate();

            // then
            then(kafkaTemplate).should(org.mockito.Mockito.times(2))
                    .send(anyString(), anyString(), anyString());
        }
    }

    private EscalationTask buildOverdueTask() {
        final Instant openedAt = Instant.now().minusSeconds(20 * 60L);
        return EscalationTask.create(
                UUID.randomUUID(),
                TENANT_ID,
                openedAt,
                15,
                "CRITICAL",
                "High CPU Usage"
        );
    }
}