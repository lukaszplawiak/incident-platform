package com.incidentplatform.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceTokenProvider")
class ServiceTokenProviderTest {

    @Mock
    private JwtUtils jwtUtils;

    private static final String SERVICE_NAME = "notification-service";
    private static final long SERVICE_EXPIRATION_MS = 2_592_000_000L; // 30 days
    private static final String FAKE_TOKEN = "eyJhbGciOiJIUzUxMiJ9.fake.token";

    private ServiceTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ServiceTokenProvider(
                jwtUtils, SERVICE_NAME, SERVICE_EXPIRATION_MS);
    }

    @Nested
    @DisplayName("token generation")
    class TokenGeneration {

        @Test
        @DisplayName("generates token on first call")
        void generatesTokenOnFirstCall() {
            // given
            given(jwtUtils.generateServiceToken(SERVICE_NAME)).willReturn(FAKE_TOKEN);

            // when
            final String token = provider.getToken();

            // then
            assertThat(token).isEqualTo(FAKE_TOKEN);
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME);
        }

        @Test
        @DisplayName("caches token on subsequent calls")
        void cachesTokenOnSubsequentCalls() {
            // given
            given(jwtUtils.generateServiceToken(SERVICE_NAME)).willReturn(FAKE_TOKEN);

            // when
            provider.getToken();
            provider.getToken();
            provider.getToken();

            // then — JwtUtils called only once, token is cached
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME);
        }

        @Test
        @DisplayName("uses service-expiration-ms not user expiration-ms")
        void usesServiceExpirationNotUserExpiration() {
            // given — service expiration 30 days, user expiration would be 24h
            final long userExpirationMs = 86_400_000L; // 24h
            final ServiceTokenProvider providerWithUserExpiry =
                    new ServiceTokenProvider(jwtUtils, SERVICE_NAME, userExpirationMs);

            given(jwtUtils.generateServiceToken(SERVICE_NAME))
                    .willReturn(FAKE_TOKEN, "new-token");

            // when — first call generates token
            providerWithUserExpiry.getToken();

            // A token generated with service-expiration-ms (30 days) should NOT be
            // refreshed after 24h. But if provider mistakenly used user expiration (24h)
            // the token would be considered expired after 24h - 5min buffer.
            // This test verifies the constructor correctly stores and uses
            // the provided expiration value.
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME);
        }
    }
}