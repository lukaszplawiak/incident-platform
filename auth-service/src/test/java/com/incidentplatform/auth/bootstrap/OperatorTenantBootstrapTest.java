package com.incidentplatform.auth.bootstrap;

import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorTenantBootstrap (backlog #0-16)")
class OperatorTenantBootstrapTest {

    private static final String EMAIL = "ops@incident-platform.local";

    @Mock private UserRepository userRepository;
    @Mock private UserService userService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private OperatorTenantBootstrap bootstrap(String email) {
        return new OperatorTenantBootstrap(email, userRepository, userService);
    }

    @Test
    @DisplayName("does nothing when no admin email is configured")
    void disabled() {
        bootstrap("  ").run(null);

        verifyNoInteractions(userRepository, userService);
    }

    @Test
    @DisplayName("invites the first ADMIN inside the platform-operator tenant, then clears the context")
    void invitesFirstAdmin() {
        given(userRepository.existsByTenantId(ReservedTenants.PLATFORM_OPERATOR)).willReturn(false);
        final AtomicReference<String> tenantDuringCreate = new AtomicReference<>();
        willAnswer(inv -> {
            tenantDuringCreate.set(TenantContext.getOrNull());
            return null;
        }).given(userService).createUser(any());

        bootstrap(EMAIL).run(null);

        then(userService).should().createUser(
                new CreateUserRequest(EMAIL, List.of(SecurityRoles.ROLE_ADMIN)));
        assertThat(tenantDuringCreate.get()).isEqualTo(ReservedTenants.PLATFORM_OPERATOR);
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("does nothing once the operator tenant has a user")
    void alreadyBootstrapped() {
        given(userRepository.existsByTenantId(ReservedTenants.PLATFORM_OPERATOR)).willReturn(true);

        bootstrap(EMAIL).run(null);

        then(userService).should(never()).createUser(any());
    }

    @Test
    @DisplayName("a replica that loses the race logs and carries on")
    void lostRace() {
        given(userRepository.existsByTenantId(ReservedTenants.PLATFORM_OPERATOR)).willReturn(false);
        given(userService.createUser(any())).willThrow(new BusinessException(
                ErrorCodes.EMAIL_ALREADY_EXISTS, "exists", HttpStatus.CONFLICT));
        assertThatCode(() -> bootstrap(EMAIL).run(null)).doesNotThrowAnyException();

        willThrow(new DataIntegrityViolationException("dup")).given(userService).createUser(any());
        assertThatCode(() -> bootstrap(EMAIL).run(null)).doesNotThrowAnyException();
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("any other failure stops startup, and the tenant context is still cleared")
    void otherFailure() {
        given(userRepository.existsByTenantId(ReservedTenants.PLATFORM_OPERATOR)).willReturn(false);
        given(userService.createUser(any())).willThrow(new BusinessException(
                ErrorCodes.VALIDATION_FAILED, "bad email", HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> bootstrap(EMAIL).run(null)).isInstanceOf(BusinessException.class);
        assertThat(TenantContext.isSet()).isFalse();
    }
}
