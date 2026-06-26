package com.incidentplatform.oncall.validation;

import com.incidentplatform.oncall.dto.CreateOncallScheduleRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates that {@link CreateOncallScheduleRequest#startsAt()} is strictly
 * before {@link CreateOncallScheduleRequest#endsAt()}.
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
        implements ConstraintValidator<StartBeforeEnd, CreateOncallScheduleRequest> {

    @Override
    public boolean isValid(CreateOncallScheduleRequest request,
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