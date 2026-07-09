package com.incidentplatform.ingestion.alertmanager;

import com.incidentplatform.shared.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlertManagerTokenRefresher")
class AlertManagerTokenRefresherTest {

    @Mock
    private JwtUtils jwtUtils;

    @TempDir
    Path tempDir;

    private Path tokenFile;
    private static final String FAKE_TOKEN = "eyJhbGciOiJIUzUxMiJ9.fake.token";
    private static final Duration SERVICE_TOKEN_TTL = Duration.ofHours(1); // 1h — matches PT1H default

    @BeforeEach
    void setUp() {
        tokenFile = tempDir.resolve("ingestor-token");
    }

    private AlertManagerTokenRefresher createRefresher(boolean enabled) {
        // getServiceTokenTtl() is called inside refreshToken() — stub it
        org.mockito.BDDMockito.lenient()
                .when(jwtUtils.getServiceTokenTtl()).thenReturn(SERVICE_TOKEN_TTL);
        return new AlertManagerTokenRefresher(
                jwtUtils,
                tokenFile.toString(),
                enabled
        );
    }

    private AlertManagerTokenRefresher createRefresherWithPath(String path) {
        org.mockito.BDDMockito.lenient()
                .when(jwtUtils.getServiceTokenTtl()).thenReturn(SERVICE_TOKEN_TTL);
        return new AlertManagerTokenRefresher(
                jwtUtils,
                path,
                true
        );
    }

    // ─── Initial token provisioning ───────────────────────────────────────────
    // The initial token is created by scripts/generate-alertmanager-token.sh
    // before the stack starts — NOT by this class on startup.
    // These tests verify that no token is written during application startup.

    @Nested
    @DisplayName("startup behaviour")
    class StartupBehaviour {

        @Test
        @DisplayName("does not write token file on application startup")
        void doesNotWriteTokenFileOnStartup() {
            // given
            final AlertManagerTokenRefresher refresher = createRefresher(true);

            // when — simulate application context startup, no explicit call
            // refresher has no @EventListener — nothing fires on startup

            // then — no file written, no JwtUtils call
            then(jwtUtils).should(never()).generateServiceToken("alertmanager");
            assertThat(tokenFile).doesNotExist();
        }

        @Test
        @DisplayName("initial token must be pre-provisioned by the generate script")
        void initialTokenMustBeProvisionedExternally() {
            // This test documents the expected operational contract:
            // docker/secrets/alertmanager-token.txt must exist before the stack starts.
            // AlertManagerTokenRefresher only rotates an existing token — it does not
            // create it. If the file is missing, Alertmanager will fail to authenticate.
            assertThat(tokenFile)
                    .as("Token file must be created by scripts/generate-alertmanager-token.sh " +
                            "before starting the stack — AlertManagerTokenRefresher does not " +
                            "create the initial token")
                    .doesNotExist();
        }
    }

    // ─── Scheduled token rotation ─────────────────────────────────────────────

    @Nested
    @DisplayName("refreshToken")
    class RefreshToken {

        @Test
        @DisplayName("writes new token to file overwriting previous value")
        void overwritesPreviousToken() throws IOException {
            // given
            final AlertManagerTokenRefresher refresher = createRefresher(true);
            Files.writeString(tokenFile, "old-token");

            given(jwtUtils.generateServiceToken("alertmanager"))
                    .willReturn("new-token");

            // when
            refresher.refreshToken();

            // then
            assertThat(Files.readString(tokenFile, StandardCharsets.UTF_8))
                    .isEqualTo("new-token");
        }

        @Test
        @DisplayName("creates parent directories if they don't exist")
        void createsParentDirectories() throws IOException {
            // given
            final Path nestedPath = tempDir.resolve("secrets/nested/ingestor-token");
            final AlertManagerTokenRefresher refresher =
                    createRefresherWithPath(nestedPath.toString());
            given(jwtUtils.generateServiceToken("alertmanager")).willReturn(FAKE_TOKEN);

            // when
            refresher.refreshToken();

            // then
            assertThat(nestedPath).exists();
            assertThat(Files.readString(nestedPath, StandardCharsets.UTF_8))
                    .isEqualTo(FAKE_TOKEN);
        }

        @Test
        @DisplayName("does not throw when JwtUtils fails — existing token stays valid")
        void doesNotThrowWhenJwtUtilsFails() {
            // given
            final AlertManagerTokenRefresher refresher = createRefresher(true);
            given(jwtUtils.generateServiceToken("alertmanager"))
                    .willThrow(new RuntimeException("JWT secret not configured"));

            // when / then
            assertThatCode(refresher::refreshToken)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("token file contains no trailing newline")
        void tokenFileHasNoTrailingNewline() throws IOException {
            // given
            final AlertManagerTokenRefresher refresher = createRefresher(true);
            given(jwtUtils.generateServiceToken("alertmanager")).willReturn(FAKE_TOKEN);

            // when
            refresher.refreshToken();

            // then
            final byte[] bytes = Files.readAllBytes(tokenFile);
            assertThat(bytes[bytes.length - 1])
                    .as("Token file must not end with newline")
                    .isNotEqualTo((byte) '\n');
        }

        @Test
        @DisplayName("refreshes token on each scheduled call")
        void refreshesOnEachScheduledCall() {
            // given
            final AlertManagerTokenRefresher refresher = createRefresher(true);
            given(jwtUtils.generateServiceToken("alertmanager")).willReturn(FAKE_TOKEN);

            // when
            refresher.refreshToken();
            refresher.refreshToken();
            refresher.refreshToken();

            // then
            then(jwtUtils).should(times(3)).generateServiceToken("alertmanager");
        }

        @Test
        @DisplayName("does nothing when disabled")
        void doesNothingWhenDisabled() {
            // given
            final AlertManagerTokenRefresher refresher = createRefresher(false);

            // when
            refresher.refreshToken();

            // then
            then(jwtUtils).should(never()).generateServiceToken("alertmanager");
            assertThat(tokenFile).doesNotExist();
        }

        @Test
        @DisplayName("does nothing when token file path is blank")
        void doesNothingWhenPathIsBlank() {
            // given
            final AlertManagerTokenRefresher refresher =
                    createRefresherWithPath("");

            // when
            refresher.refreshToken();

            // then
            then(jwtUtils).should(never()).generateServiceToken("alertmanager");
        }
    }
}