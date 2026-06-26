package com.incidentplatform.oncall.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that the {@code startsAt} field is strictly before {@code endsAt}
 * on the annotated type.
 *
 * <p>The annotated class must expose {@code startsAt()} and {@code endsAt()}
 * accessors returning {@link java.time.Instant}. Both fields must be
 * non-null for this constraint to fire; null values are handled by the
 * field-level {@code @NotNull} constraints and are treated as valid here
 * to avoid duplicate error messages.
 *
 * <p>Applied at class level so that both fields are available simultaneously
 * — field-level constraints cannot compare two fields against each other.
 *
 * <p>Validated by {@link StartBeforeEndValidator}.
 */
@Documented
@Constraint(validatedBy = StartBeforeEndValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StartBeforeEnd {

    String message() default "startsAt must be before endsAt";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}