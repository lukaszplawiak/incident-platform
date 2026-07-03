package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.ChangePasswordRequest;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordService")
class PasswordServiceTest {

    @Mock
    private UserRepository userRepository;

    private PasswordService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String CURRENT_PASSWORD = "CurrentPass123!";
    private static final String NEW_PASSWORD = "NewPassword456!";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        service = new PasswordService(userRepository);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── changePassword — success ─────────────────────────────────────────

    @Nested
    @DisplayName("changePassword — success")
    class ChangePasswordSuccess {

        @Test
        @DisplayName("updates password hash when current password is correct")
        void updatesPasswordHash() {
            // given
            final User user = buildUserWithPassword(CURRENT_PASSWORD);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            service.changePassword(buildPrincipal(),
                    new ChangePasswordRequest(CURRENT_PASSWORD, NEW_PASSWORD));

            // then — new BCrypt hash is set, never stores plain text
            final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            then(userRepository).should().save(captor.capture());
            final String newHash = captor.getValue().getPasswordHash();

            assertThat(newHash).isNotNull();
            assertThat(newHash).isNotEqualTo(NEW_PASSWORD);
            assertThat(newHash).startsWith("$2a$");
            assertThat(ENCODER.matches(NEW_PASSWORD, newHash)).isTrue();
        }

        @Test
        @DisplayName("new hash does not match old password")
        void newHashDoesNotMatchOldPassword() {
            // given
            final User user = buildUserWithPassword(CURRENT_PASSWORD);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            service.changePassword(buildPrincipal(),
                    new ChangePasswordRequest(CURRENT_PASSWORD, NEW_PASSWORD));

            // then
            final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            then(userRepository).should().save(captor.capture());
            assertThat(ENCODER.matches(CURRENT_PASSWORD,
                    captor.getValue().getPasswordHash())).isFalse();
        }
    }

    // ── changePassword — wrong current password ───────────────────────────

    @Nested
    @DisplayName("changePassword — wrong current password")
    class WrongCurrentPassword {

        @Test
        @DisplayName("throws 401 when current password is wrong")
        void throws401OnWrongPassword() {
            // given
            final User user = buildUserWithPassword(CURRENT_PASSWORD);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));

            // when / then
            assertThatThrownBy(() ->
                    service.changePassword(buildPrincipal(),
                            new ChangePasswordRequest("WrongPassword!", NEW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid credentials")
                    .extracting(ex -> ((BusinessException) ex).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("does not save user when current password is wrong")
        void doesNotSaveOnWrongPassword() {
            // given
            final User user = buildUserWithPassword(CURRENT_PASSWORD);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));

            // when
            assertThatThrownBy(() ->
                    service.changePassword(buildPrincipal(),
                            new ChangePasswordRequest("WrongPassword!", NEW_PASSWORD)))
                    .isInstanceOf(BusinessException.class);

            // then
            then(userRepository).should().findByIdAndTenantId(USER_ID, TENANT_ID);
            then(userRepository).shouldHaveNoMoreInteractions();
        }
    }

    // ── changePassword — user not found ───────────────────────────────────

    @Nested
    @DisplayName("changePassword — user not found")
    class UserNotFound {

        @Test
        @DisplayName("throws ResourceNotFoundException when user not in tenant")
        void throwsNotFound() {
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.changePassword(buildPrincipal(),
                            new ChangePasswordRequest(CURRENT_PASSWORD, NEW_PASSWORD)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private User buildUserWithPassword(String rawPassword) {
        return User.forTesting(USER_ID, TENANT_ID, "u@example.com",
                ENCODER.encode(rawPassword), true, List.of("ROLE_RESPONDER"));
    }

    private UserPrincipal buildPrincipal() {
        return new UserPrincipal(USER_ID, TENANT_ID, "u@example.com",
                List.of("ROLE_RESPONDER"));
    }
}