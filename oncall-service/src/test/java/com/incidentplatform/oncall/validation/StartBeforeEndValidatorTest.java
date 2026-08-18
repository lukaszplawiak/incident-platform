package com.incidentplatform.oncall.validation;

import com.incidentplatform.oncall.dto.CreateOncallScheduleRequest;
import com.incidentplatform.oncall.dto.UpdateOncallScheduleRequest;
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

    // ── backlog #43: same validator, a different, unrelated record ─────────

    /**
     * The actual regression test for backlog #43's
     * {@link HasScheduleWindow} fix. Before it, {@link StartBeforeEndValidator}
     * was hard-typed to {@code ConstraintValidator<StartBeforeEnd,
     * CreateOncallScheduleRequest>} — annotating an unrelated record (even
     * one with an identical field shape) with {@code @StartBeforeEnd}
     * would have failed at validation time with "no validator found,"
     * not silently skipped the check. This uses the same real
     * {@code Validator} instance as every test above, so it genuinely
     * exercises Jakarta Validation's validator-resolution algorithm
     * end-to-end, not a mocked assumption about how it behaves.
     */
    @Nested
    @DisplayName("same validator also resolves for UpdateOncallScheduleRequest (backlog #43)")
    class UpdateRequestAlsoValidated {

        @Test
        @DisplayName("endsAt before startsAt — violation on endsAt field, same as CreateOncallScheduleRequest")
        void endsBeforeStarts() {
            final var request = buildUpdateRequest(
                    Instant.parse("2099-01-08T00:00:00Z"),
                    Instant.parse("2099-01-01T00:00:00Z")
            );

            final Set<ConstraintViolation<UpdateOncallScheduleRequest>> violations =
                    validator.validate(request);

            final var temporal = violations.stream()
                    .filter(v -> v.getPropertyPath().toString().equals("endsAt")
                            && v.getMessage().equals("startsAt must be before endsAt"))
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(temporal).hasSize(1);
        }

        @Test
        @DisplayName("startsAt strictly before endsAt — valid")
        void startsBeforeEnds() {
            final var request = buildUpdateRequest(
                    Instant.parse("2099-01-01T00:00:00Z"),
                    Instant.parse("2099-01-08T00:00:00Z")
            );

            final Set<ConstraintViolation<UpdateOncallScheduleRequest>> violations =
                    validator.validate(request);

            final var temporal = violations.stream()
                    .filter(v -> v.getPropertyPath().toString().equals("endsAt")
                            && v.getMessage().equals("startsAt must be before endsAt"))
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(temporal).isEmpty();
        }

        private UpdateOncallScheduleRequest buildUpdateRequest(Instant startsAt, Instant endsAt) {
            return new UpdateOncallScheduleRequest(
                    null,
                    "user-2",
                    "Anna Nowak",
                    "anna@example.com",
                    "+48100200301",
                    "U9876543210",
                    "SECONDARY",
                    startsAt,
                    endsAt,
                    "replacement"
            );
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