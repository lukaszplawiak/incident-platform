package com.incidentplatform.notification.slack;

import com.incidentplatform.notification.domain.SlackMessageTs;
import com.incidentplatform.notification.repository.SlackMessageTsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Tests for {@link SlackMessageStore} — rewritten from an in-memory
 * {@code ConcurrentHashMap} to a Postgres-backed
 * {@link SlackMessageTsRepository}. See that class's Javadoc for the two
 * bugs this fix addressed: state not shared across replicas, and (the more
 * serious half) {@code save} never actually being called anywhere before
 * this fix — covered separately in
 * {@code SlackNotificationChannelSendTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SlackMessageStore")
class SlackMessageStoreTest {

    @Mock
    private SlackMessageTsRepository repository;

    private SlackMessageStore store;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String TENANT_ID = "acme-corp";
    private static final String CHANNEL = "#incidents";
    private static final String TS = "1234567890.123456";

    @BeforeEach
    void setUp() {
        store = new SlackMessageStore(repository);
    }

    @Nested
    @DisplayName("save")
    class Save {

        @Test
        @DisplayName("persists a row via the repository")
        void persistsRow() {
            store.save(INCIDENT_ID, CHANNEL, TENANT_ID, TS);

            final ArgumentCaptor<SlackMessageTs> captor =
                    ArgumentCaptor.forClass(SlackMessageTs.class);
            then(repository).should().save(captor.capture());

            final SlackMessageTs saved = captor.getValue();
            assertThat(saved.getIncidentId()).isEqualTo(INCIDENT_ID);
            assertThat(saved.getChannel()).isEqualTo(CHANNEL);
            assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(saved.getTs()).isEqualTo(TS);
        }

        @Test
        @DisplayName("does nothing when ts is null — never persists a broken row")
        void doesNothingOnNullTs() {
            store.save(INCIDENT_ID, CHANNEL, TENANT_ID, null);

            then(repository).should(never()).save(any());
        }

        @Test
        @DisplayName("does nothing when ts is blank")
        void doesNothingOnBlankTs() {
            store.save(INCIDENT_ID, CHANNEL, TENANT_ID, "   ");

            then(repository).should(never()).save(any());
        }
    }

    @Nested
    @DisplayName("find")
    class Find {

        @Test
        @DisplayName("returns the ts when a row exists")
        void returnsTsWhenPresent() {
            given(repository.findByIdIncidentIdAndIdChannel(INCIDENT_ID, CHANNEL))
                    .willReturn(Optional.of(
                            new SlackMessageTs(INCIDENT_ID, CHANNEL, TENANT_ID, TS)));

            assertThat(store.find(INCIDENT_ID, CHANNEL)).contains(TS);
        }

        @Test
        @DisplayName("returns empty when no row exists")
        void returnsEmptyWhenAbsent() {
            given(repository.findByIdIncidentIdAndIdChannel(INCIDENT_ID, CHANNEL))
                    .willReturn(Optional.empty());

            assertThat(store.find(INCIDENT_ID, CHANNEL)).isEmpty();
        }
    }

    @Nested
    @DisplayName("findAllChannelsForIncident")
    class FindAllChannelsForIncident {

        @Test
        @DisplayName("returns every channel with a stored row for the incident")
        void returnsAllChannels() {
            given(repository.findByIdIncidentId(INCIDENT_ID)).willReturn(List.of(
                    new SlackMessageTs(INCIDENT_ID, "#incidents", TENANT_ID, TS),
                    new SlackMessageTs(INCIDENT_ID, "U0123456789", TENANT_ID, TS)
            ));

            assertThat(store.findAllChannelsForIncident(INCIDENT_ID))
                    .containsExactlyInAnyOrder("#incidents", "U0123456789");
        }
    }

    @Nested
    @DisplayName("removeAllForIncident")
    class RemoveAllForIncident {

        @Test
        @DisplayName("delegates to the repository's bulk delete")
        void delegatesToRepository() {
            store.removeAllForIncident(INCIDENT_ID);

            then(repository).should().deleteByIdIncidentId(INCIDENT_ID);
        }
    }

    @Nested
    @DisplayName("deleteOlderThan")
    class DeleteOlderThan {

        @Test
        @DisplayName("delegates to the repository and returns the deleted count")
        void delegatesAndReturnsCount() {
            final Instant threshold = Instant.now();
            given(repository.deleteByCreatedAtBefore(threshold)).willReturn(3);

            assertThat(store.deleteOlderThan(threshold)).isEqualTo(3);
        }
    }
}