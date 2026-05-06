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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlertmanagerTokenRefresher")
class AlertManagerTokenRefresherTest {

    @Mock
    private JwtUtils jwtUtils;

    @TempDir
    Path tempDir;

    private Path tokenFile;
    private static final String FAKE_TOKEN = "eyJhbGciOiJIUzUxMiJ9.fake.token";
    private static final long EXPIRATION_MS = 86_400_000L;

    @BeforeEach
    void setUp() {
        tokenFile = tempDir.resolve("ingestor-token");
        given(jwtUtils.generateServiceToken("alertmanager"))
                .willReturn(FAKE_TOKEN);
    }

    private AlertmanagerTokenRefresher createRefresher(boolean enabled) {
        return new AlertmanagerTokenRefresher(
                jwtUtils,
                tokenFile.toString(),
                EXPIRATION_MS,
                enabled
        );
    }

    private AlertmanagerTokenRefresher createRefresherWithPath(String path) {
        return new AlertmanagerTokenRefresher(
                jwtUtils,
                path,
                EXPIRATION_MS,
                true
        );
    }

    // ─── generateTokenOnStartup ───────────────────────────────────────────────

    @Nested
    @DisplayName("generateTokenOnStartup")
    class GenerateTokenOnStartup {

        @Test
        @DisplayName("writes token to file on application startup")
        void writesTokenToFileOnStartup() throws IOException {
            // given
            final AlertmanagerTokenRefresher refresher = createRefresher(true);

            // when
            refresher.generateTokenOnStartup();

            // then
            assertThat(tokenFile).exists();
            assertThat(Files.readString(tokenFile, StandardCharsets.UTF_8))
                    .isEqualTo(FAKE_TOKEN);
        }

        @Test
        @DisplayName("does nothing when disabled")
        void doesNothingWhenDisabled() {
            // given
            final AlertmanagerTokenRefresher refresher = createRefresher(false);

            // when
            refresher.generateTokenOnStartup();

            // then
            then(jwtUtils).should(never()).generateServiceToken("alertmanager");
            assertThat(tokenFile).doesNotExist();
        }

        @Test
        @DisplayName("does nothing when token file path is blank")
        void doesNothingWhenPathIsBlank() {
            // given
            final AlertmanagerTokenRefresher refresher =
                    createRefresherWithPath("");

            // when
            refresher.generateTokenOnStartup();

            // then
            then(jwtUtils).should(never()).generateServiceToken("alertmanager");
        }
    }

    // ─── refreshToken ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refreshToken")
    class RefreshToken {

        @Test
        @DisplayName("writes new token to file overwriting previous value")
        void overwritesPreviousToken() throws IOException {
            // given
            final AlertmanagerTokenRefresher refresher = createRefresher(true);
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
            final AlertmanagerTokenRefresher refresher =
                    createRefresherWithPath(nestedPath.toString());

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
            final AlertmanagerTokenRefresher refresher = createRefresher(true);
            given(jwtUtils.generateServiceToken("alertmanager"))
                    .willThrow(new RuntimeException("JWT secret not configured"));

            // when / then — must not propagate exception
            org.assertj.core.api.Assertions.assertThatCode(refresher::refreshToken)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("token file contains no trailing newline")
        void tokenFileHasNoTrailingNewline() throws IOException {
            // given
            final AlertmanagerTokenRefresher refresher = createRefresher(true);

            // when
            refresher.refreshToken();

            // then — Alertmanager reads raw file, trailing newline would corrupt token
            final byte[] bytes = Files.readAllBytes(tokenFile);
            assertThat(bytes[bytes.length - 1])
                    .as("Token file must not end with newline")
                    .isNotEqualTo((byte) '\n');
        }

        @Test
        @DisplayName("refreshes token on each scheduled call")
        void refreshesOnEachScheduledCall() {
            // given
            final AlertmanagerTokenRefresher refresher = createRefresher(true);

            // when
            refresher.refreshToken();
            refresher.refreshToken();
            refresher.refreshToken();

            // then — generateServiceToken called once per refresh
            then(jwtUtils).should(times(3)).generateServiceToken("alertmanager");
        }
    }
}