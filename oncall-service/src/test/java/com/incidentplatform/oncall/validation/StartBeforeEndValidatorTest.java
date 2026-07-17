package com.incidentplatform.oncall.validation;

import com.incidentplatform.oncall.dto.CreateOncallScheduleRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StartBeforeEnd")
class StartBeforeEndValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // ── valid cases ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("valid — no temporal violation")
    class Valid {

        @Test
        @DisplayName("startsAt strictly before endsAt — valid")
        void startsBeforeEnds() {
            final var request = buildRequest(
                    Instant.parse("2099-01-01T00:00:00Z"),
                    Instant.parse("2099-01-08T00:00:00Z")
            );

            final Set<ConstraintViolation<CreateOncallScheduleRequest>> violations =
                    validator.validate(request);

            assertThat(temporalViolations(violations)).isEmpty();
        }

        @Test
        @DisplayName("startsAt null — deferred to @NotNull, no temporal violation")
        void startsAtNull_noTemporalViolation() {
            final var request = buildRequest(null, Instant.parse("2099-01-08T00:00:00Z"));

            final Set<ConstraintViolation<CreateOncallScheduleRequest>> violations =
                    validator.validate(request);

            assertThat(temporalViolations(violations)).isEmpty();
        }

        @Test
        @DisplayName("endsAt null — deferred to @NotNull, no temporal violation")
        void endsAtNull_noTemporalViolation() {
            final var request = buildRequest(Instant.parse("2099-01-01T00:00:00Z"), null);

            final Set<ConstraintViolation<CreateOncallScheduleRequest>> violations =
                    validator.validate(request);

            assertThat(temporalViolations(violations)).isEmpty();
        }
    }

    // ── invalid cases ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("invalid — temporal violation reported on endsAt")
    class Invalid {

        @Test
        @DisplayName("endsAt before startsAt — violation on endsAt field")
        void endsBeforeStarts() {
            final var request = buildRequest(
                    Instant.parse("2099-01-08T00:00:00Z"),
                    Instant.parse("2099-01-01T00:00:00Z")
            );

            final Set<ConstraintViolation<CreateOncallScheduleRequest>> violations =
                    validator.validate(request);

            assertThat(temporalViolations(violations)).hasSize(1);
            final ConstraintViolation<CreateOncallScheduleRequest> v =
                    temporalViolations(violations).iterator().next();
            assertThat(v.getPropertyPath().toString()).isEqualTo("endsAt");
            assertThat(v.getMessage()).isEqualTo("startsAt must be before endsAt");
        }

        @Test
        @DisplayName("endsAt equal to startsAt — violation (not strictly before)")
        void endsEqualToStarts() {
            final Instant same = Instant.parse("2099-01-01T00:00:00Z");
            final var request = buildRequest(same, same);

            final Set<ConstraintViolation<CreateOncallScheduleRequest>> violations =
                    validator.validate(request);

            assertThat(temporalViolations(violations)).hasSize(1);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Set<ConstraintViolation<CreateOncallScheduleRequest>> temporalViolations(
            Set<ConstraintViolation<CreateOncallScheduleRequest>> all) {
        return all.stream()
                .filter(v -> v.getPropertyPath().toString().equals("endsAt")
                        && v.getMessage().equals("startsAt must be before endsAt"))
                .collect(java.util.stream.Collectors.toSet());
    }

    private CreateOncallScheduleRequest buildRequest(Instant startsAt, Instant endsAt) {
        return new CreateOncallScheduleRequest(
                null,
                "user-1",
                "Jan Kowalski",
                "jan@example.com",
                "+48100200300",
                "U0123456789",
                "PRIMARY",
                startsAt,
                endsAt,
                "test notes"
        );
    }
}