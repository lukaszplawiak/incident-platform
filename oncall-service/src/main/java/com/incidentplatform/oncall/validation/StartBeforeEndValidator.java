package com.incidentplatform.oncall.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates that {@link HasScheduleWindow#startsAt()} is strictly before
 * {@link HasScheduleWindow#endsAt()}.
 *
 * <p>Fixed (backlog #43): previously declared
 * {@code ConstraintValidator<StartBeforeEnd, CreateOncallScheduleRequest>}
 * — hard-typed to one specific record. When
 * {@code UpdateOncallScheduleRequest} needed the identical check, Jakarta
 * Validation would have failed to resolve a validator for it (an
 * unrelated record doesn't match a validator declared for a different,
 * specific type, even with an identical field shape). Fixed by
 * validating against {@link HasScheduleWindow} instead — both
 * {@code CreateOncallScheduleRequest} and {@code UpdateOncallScheduleRequest}
 * implement it, and any future request record needing this same check
 * can too, without a new validator.
 *
 * <p>Returns {@code true} (valid) when either field is {@code null} — null
 * values are already covered by {@code @NotNull} constraints on the
 * respective fields. Reporting a second error here would produce duplicate,
 * confusing messages in the {@code ErrorResponse.errors} list.
 *
 * <p>When invalid, the default constraint message is attached to the
 * {@code endsAt} field path so that API clients can highlight the correct
 * field in their UIs, rather than receiving a class-level error with no
 * field reference.
 */
public class StartBeforeEndValidator
        implements ConstraintValidator<StartBeforeEnd, HasScheduleWindow> {

    @Override
    public boolean isValid(HasScheduleWindow request,
                           ConstraintValidatorContext context) {

        if (request.startsAt() == null || request.endsAt() == null) {
            return true;
        }

        if (request.startsAt().isBefore(request.endsAt())) {
            return true;
        }

        // Attach the violation to the endsAt field so clients receive a
        // field-level error path ("endsAt") instead of a class-level one.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                        context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("endsAt")
                .addConstraintViolation();

        return false;
    }
}