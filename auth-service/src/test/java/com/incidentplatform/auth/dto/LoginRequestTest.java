package com.incidentplatform.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoginRequest")
class LoginRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Nested
    @DisplayName("valid")
    class Valid {

        @Test
        @DisplayName("valid email and non-blank password — no violations")
        void validRequest() {
            final var request = new LoginRequest("user@example.com", "password123");

            assertThat(validator.validate(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("invalid")
    class Invalid {

        @Test
        @DisplayName("blank email — violation")
        void blankEmail() {
            final var request = new LoginRequest("", "password123");

            final Set<ConstraintViolation<LoginRequest>> violations =
                    validator.validate(request);

            assertThat(violations)
                    .anySatisfy(v -> assertThat(v.getPropertyPath().toString())
                            .isEqualTo("email"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-an-email", "missing-at-sign.com", "@no-local-part.com"})
        @DisplayName("malformed email — violation")
        void malformedEmail(String email) {
            final var request = new LoginRequest(email, "password123");

            final Set<ConstraintViolation<LoginRequest>> violations =
                    validator.validate(request);

            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("blank password — violation")
        void blankPassword() {
            final var request = new LoginRequest("user@example.com", "");

            final Set<ConstraintViolation<LoginRequest>> violations =
                    validator.validate(request);

            assertThat(violations)
                    .anySatisfy(v -> assertThat(v.getPropertyPath().toString())
                            .isEqualTo("password"));
        }

        @Test
        @DisplayName("both blank — two violations")
        void bothBlank() {
            final var request = new LoginRequest("", "");

            assertThat(validator.validate(request)).hasSize(2);
        }
    }
}