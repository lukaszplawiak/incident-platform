package com.incidentplatform.incident.domain;

import com.incidentplatform.shared.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IncidentFsm")
class IncidentFsmTest {

    @Nested
    @DisplayName("Allowed transitions - validateTransition should NOT throw")
    class AllowedTransitions {

        @Test
        @DisplayName("OPEN → ACKNOWLEDGED should be allowed")
        void openToAcknowledgedShouldBeAllowed() {
            // when/then
            IncidentFsm.validateTransition(
                    IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED);
        }

        @Test
        @DisplayName("OPEN → ESCALATED should be allowed")
        void openToEscalatedShouldBeAllowed() {
            IncidentFsm.validateTransition(
                    IncidentStatus.OPEN, IncidentStatus.ESCALATED);
        }

        @Test
        @DisplayName("ACKNOWLEDGED → RESOLVED should be allowed")
        void acknowledgedToResolvedShouldBeAllowed() {
            IncidentFsm.validateTransition(
                    IncidentStatus.ACKNOWLEDGED, IncidentStatus.RESOLVED);
        }

        @Test
        @DisplayName("ESCALATED → ACKNOWLEDGED should be allowed")
        void escalatedToAcknowledgedShouldBeAllowed() {
            IncidentFsm.validateTransition(
                    IncidentStatus.ESCALATED, IncidentStatus.ACKNOWLEDGED);
        }

        @Test
        @DisplayName("RESOLVED → CLOSED should be allowed")
        void resolvedToClosedShouldBeAllowed() {
            IncidentFsm.validateTransition(
                    IncidentStatus.RESOLVED, IncidentStatus.CLOSED);
        }
    }

    @Nested
    @DisplayName("Forbidden transitions - validateTransition should throw BusinessException")
    class ForbiddenTransitions {

        @ParameterizedTest(name = "{0} → {1} should be forbidden")
        @CsvSource({
                // From OPEN
                "OPEN, OPEN",
                "OPEN, RESOLVED",
                "OPEN, CLOSED",

                // From ACKNOWLEDGED
                "ACKNOWLEDGED, OPEN",
                "ACKNOWLEDGED, ACKNOWLEDGED",
                "ACKNOWLEDGED, ESCALATED",
                "ACKNOWLEDGED, CLOSED",

                // From ESCALATED
                "ESCALATED, OPEN",
                "ESCALATED, ESCALATED",
                "ESCALATED, RESOLVED",
                "ESCALATED, CLOSED",

                // From RESOLVED
                "RESOLVED, OPEN",
                "RESOLVED, ACKNOWLEDGED",
                "RESOLVED, ESCALATED",
                "RESOLVED, RESOLVED",

                // From CLOSED
                "CLOSED, OPEN",
                "CLOSED, ACKNOWLEDGED",
                "CLOSED, ESCALATED",
                "CLOSED, RESOLVED",
                "CLOSED, CLOSED"
        })
        void shouldThrowForForbiddenTransition(
                IncidentStatus from, IncidentStatus to) {

            assertThatThrownBy(() -> IncidentFsm.validateTransition(from, to))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());
        }
    }

    @Nested
    @DisplayName("isTransitionAllowed")
    class IsTransitionAllowed {

        @ParameterizedTest(name = "{0} → {1} should return true")
        @CsvSource({
                "OPEN, ACKNOWLEDGED",
                "OPEN, ESCALATED",
                "ACKNOWLEDGED, RESOLVED",
                "ESCALATED, ACKNOWLEDGED",
                "RESOLVED, CLOSED"
        })
        void shouldReturnTrueForAllowedTransitions(
                IncidentStatus from, IncidentStatus to) {

            assertThat(IncidentFsm.isTransitionAllowed(from, to)).isTrue();
        }

        @ParameterizedTest(name = "{0} → {1} should return false")
        @CsvSource({
                "OPEN, CLOSED",
                "OPEN, RESOLVED",
                "ACKNOWLEDGED, OPEN",
                "RESOLVED, OPEN",
                "CLOSED, OPEN",
                "CLOSED, RESOLVED"
        })
        void shouldReturnFalseForForbiddenTransitions(
                IncidentStatus from, IncidentStatus to) {

            assertThat(IncidentFsm.isTransitionAllowed(from, to)).isFalse();
        }
    }

    @Nested
    @DisplayName("isTerminalState")
    class IsTerminalState {

        @Test
        @DisplayName("CLOSED should be terminal state")
        void closedShouldBeTerminal() {
            assertThat(IncidentFsm.isTerminalState(IncidentStatus.CLOSED)).isTrue();
        }

        @ParameterizedTest(name = "{0} should NOT be terminal state")
        @EnumSource(
                value = IncidentStatus.class,
                names = {"CLOSED"},
                mode = EnumSource.Mode.EXCLUDE
        )
        void nonClosedStatusesShouldNotBeTerminal(IncidentStatus status) {
            assertThat(IncidentFsm.isTerminalState(status)).isFalse();
        }
    }

    @Nested
    @DisplayName("getAllowedTransitions")
    class GetAllowedTransitions {

        @Test
        @DisplayName("OPEN should allow ACKNOWLEDGED and ESCALATED")
        void openShouldAllowAcknowledgedAndEscalated() {
            final Set<IncidentStatus> allowed =
                    IncidentFsm.getAllowedTransitions(IncidentStatus.OPEN);

            assertThat(allowed)
                    .containsExactlyInAnyOrder(
                            IncidentStatus.ACKNOWLEDGED,
                            IncidentStatus.ESCALATED);
        }

        @Test
        @DisplayName("ACKNOWLEDGED should allow only RESOLVED")
        void acknowledgedShouldAllowOnlyResolved() {
            final Set<IncidentStatus> allowed =
                    IncidentFsm.getAllowedTransitions(IncidentStatus.ACKNOWLEDGED);

            assertThat(allowed)
                    .containsExactly(IncidentStatus.RESOLVED);
        }

        @Test
        @DisplayName("ESCALATED should allow only ACKNOWLEDGED")
        void escalatedShouldAllowOnlyAcknowledged() {
            final Set<IncidentStatus> allowed =
                    IncidentFsm.getAllowedTransitions(IncidentStatus.ESCALATED);

            assertThat(allowed)
                    .containsExactly(IncidentStatus.ACKNOWLEDGED);
        }

        @Test
        @DisplayName("RESOLVED should allow only CLOSED")
        void resolvedShouldAllowOnlyClosed() {
            final Set<IncidentStatus> allowed =
                    IncidentFsm.getAllowedTransitions(IncidentStatus.RESOLVED);

            assertThat(allowed)
                    .containsExactly(IncidentStatus.CLOSED);
        }

        @Test
        @DisplayName("CLOSED should allow nothing (terminal state)")
        void closedShouldAllowNothing() {
            final Set<IncidentStatus> allowed =
                    IncidentFsm.getAllowedTransitions(IncidentStatus.CLOSED);

            assertThat(allowed).isEmpty();
        }
    }

    @Nested
    @DisplayName("Full lifecycle path validation")
    class FullLifecyclePath {

        @Test
        @DisplayName("happy path: OPEN → ACKNOWLEDGED → RESOLVED → CLOSED")
        void happyPathShouldBeValid() {
            IncidentFsm.validateTransition(
                    IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED);
            IncidentFsm.validateTransition(
                    IncidentStatus.ACKNOWLEDGED, IncidentStatus.RESOLVED);
            IncidentFsm.validateTransition(
                    IncidentStatus.RESOLVED, IncidentStatus.CLOSED);
        }

        @Test
        @DisplayName("escalation path: OPEN → ESCALATED → ACKNOWLEDGED → RESOLVED → CLOSED")
        void escalationPathShouldBeValid() {
            IncidentFsm.validateTransition(
                    IncidentStatus.OPEN, IncidentStatus.ESCALATED);
            IncidentFsm.validateTransition(
                    IncidentStatus.ESCALATED, IncidentStatus.ACKNOWLEDGED);
            IncidentFsm.validateTransition(
                    IncidentStatus.ACKNOWLEDGED, IncidentStatus.RESOLVED);
            IncidentFsm.validateTransition(
                    IncidentStatus.RESOLVED, IncidentStatus.CLOSED);
        }

        @Test
        @DisplayName("should not allow reopening CLOSED incident")
        void shouldNotAllowReopeningClosed() {
            assertThatThrownBy(() ->
                    IncidentFsm.validateTransition(
                            IncidentStatus.CLOSED, IncidentStatus.OPEN))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("should not allow skipping ACKNOWLEDGED to go directly to RESOLVED")
        void shouldNotAllowSkippingAcknowledged() {
            assertThatThrownBy(() ->
                    IncidentFsm.validateTransition(
                            IncidentStatus.OPEN, IncidentStatus.RESOLVED))
                    .isInstanceOf(BusinessException.class);
        }
    }
}