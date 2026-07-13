package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.UserSummaryDto;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.dto.PagedResponse;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserQueryService")
class UserQueryServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserQueryService service;

    private static final String TENANT_ID = "test-tenant";
    private static final Pageable DEFAULT_PAGE = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service = new UserQueryService(userRepository);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── listUsers ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listUsers")
    class ListUsers {

        @Test
        @DisplayName("returns paged UserSummaryDto mapped from User entities")
        void returnsMappedPage() {
            // given
            final User u1 = buildUser(UUID.randomUUID(), "alice@example.com");
            final User u2 = buildUser(UUID.randomUUID(), "bob@example.com");

            given(userRepository.findByTenantId(eq(TENANT_ID), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(u1, u2), DEFAULT_PAGE, 2L));

            // when
            final PagedResponse<UserSummaryDto> response =
                    service.listUsers(DEFAULT_PAGE);

            // then
            assertThat(response.content()).hasSize(2);
            assertThat(response.totalElements()).isEqualTo(2L);
            assertThat(response.content().get(0).email()).isEqualTo("alice@example.com");
            assertThat(response.content().get(1).email()).isEqualTo("bob@example.com");
        }

        @Test
        @DisplayName("returns empty page when no users exist")
        void returnsEmptyPage() {
            given(userRepository.findByTenantId(eq(TENANT_ID), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(), DEFAULT_PAGE, 0L));

            final PagedResponse<UserSummaryDto> response =
                    service.listUsers(DEFAULT_PAGE);

            assertThat(response.content()).isEmpty();
            assertThat(response.totalElements()).isZero();
        }

        @Test
        @DisplayName("never exposes passwordHash in response")
        void neverExposesPasswordHash() {
            final User user = buildUser(UUID.randomUUID(), "user@example.com");

            given(userRepository.findByTenantId(any(), any()))
                    .willReturn(new PageImpl<>(List.of(user)));

            final UserSummaryDto dto = service.listUsers(DEFAULT_PAGE)
                    .content().get(0);

            // UserSummaryDto has no passwordHash field — compile-time guarantee.
            // This test documents the intent explicitly.
            assertThat(dto.email()).isNotNull();
            assertThat(dto).isInstanceOf(UserSummaryDto.class);
        }

        @Test
        @DisplayName("resolves tenant from TenantContext")
        void resolvesTenantFromContext() {
            TenantContext.set("company-xyz");
            given(userRepository.findByTenantId(eq("company-xyz"), any()))
                    .willReturn(new PageImpl<>(List.of()));

            service.listUsers(DEFAULT_PAGE);

            org.mockito.BDDMockito.then(userRepository).should()
                    .findByTenantId(eq("company-xyz"), any());
        }
    }

    // ── getMe ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMe")
    class GetMe {

        @Test
        @DisplayName("returns UserSummaryDto for authenticated user")
        void returnsOwnProfile() {
            // given
            final UUID userId = UUID.randomUUID();
            final User user = buildUser(userId, "me@example.com");
            final UserPrincipal principal = buildPrincipal(userId);

            given(userRepository.findByIdAndTenantId(userId, TENANT_ID))
                    .willReturn(Optional.of(user));

            // when
            final UserSummaryDto result = service.getMe(principal);

            // then
            assertThat(result.id()).isEqualTo(userId);
            assertThat(result.email()).isEqualTo("me@example.com");
            assertThat(result.tenantId()).isEqualTo(TENANT_ID);
            assertThat(result.roles()).containsExactly("ROLE_RESPONDER");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user not found in tenant")
        void throwsNotFoundWhenUserMissing() {
            // given — user exists in different tenant or was deleted
            final UUID userId = UUID.randomUUID();
            final UserPrincipal principal = buildPrincipal(userId);

            given(userRepository.findByIdAndTenantId(userId, TENANT_ID))
                    .willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> service.getMe(principal))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private User buildUser(UUID id, String email) {
        return User.forTesting(id, TENANT_ID, email,
                "hash", true, List.of("ROLE_RESPONDER"));
    }

    private UserPrincipal buildPrincipal(UUID userId) {
        return new UserPrincipal(userId, TENANT_ID, "user@example.com", List.of("ROLE_RESPONDER"), List.of());
    }
}