package com.incidentplatform.ingestion.alertmanager;

import com.incidentplatform.shared.security.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Rotates the Alertmanager ingestor JWT before it expires.
 *
 * <p>The initial token is generated once by {@code scripts/generate-alertmanager-token.sh}
 * before the stack starts — this class is responsible only for keeping it fresh.
 * Alertmanager reads the token file on every outgoing request (credentials_file in
 * alertmanager.yml) so rotation is seamless: no Alertmanager restart required.
 *
 * <p>Rotation schedule: every 80% of the token lifetime (default: every 24 days
 * for a 30-day token). This ensures the file is always updated well before expiry.
 *
 * <p>If {@code alertmanager.token-file-path} is not configured or
 * {@code alertmanager.token-refresh-enabled} is false, this component is a no-op.
 */
@Component
public class AlertManagerTokenRefresher {

    private static final Logger log =
            LoggerFactory.getLogger(AlertManagerTokenRefresher.class);

    private final JwtUtils jwtUtils;
    private final String tokenFilePath;
    private final boolean enabled;

    private static final double REFRESH_FACTOR = 0.8;

    private static final String SERVICE_NAME = "alertmanager";

    public AlertManagerTokenRefresher(
            JwtUtils jwtUtils,
            @Value("${alertmanager.token-file-path:}") String tokenFilePath,
            @Value("${alertmanager.token-refresh-enabled:true}") boolean enabled) {
        this.jwtUtils      = jwtUtils;
        this.tokenFilePath = tokenFilePath;
        this.enabled       = enabled;
    }

    // Rotates the token file at 80% of the token lifetime.
    // The initial token is created by scripts/generate-alertmanager-token.sh —
    // this method only refreshes it to prevent expiry during long-running deployments.
    @Scheduled(fixedDelayString =
            "${alertmanager.token-refresh-delay-ms:"
                    + "#{@jwtUtils.getServiceTokenTtl().toMillis() * 8 / 10}}")
    public void refreshToken() {
        if (!isConfigured()) return;

        try {
            final String token = jwtUtils.generateServiceToken(SERVICE_NAME);
            writeTokenToFile(token);

            final long expirationMs = jwtUtils.getServiceTokenTtl().toMillis();
            log.info("Alertmanager ingestor token rotated: file={}, " +
                            "expiresInMs={}, nextRotationInMs={}",
                    tokenFilePath,
                    expirationMs,
                    (long) (expirationMs * REFRESH_FACTOR));

        } catch (Exception e) {
            log.error("Failed to rotate Alertmanager ingestor token: file={}, error={}",
                    tokenFilePath, e.getMessage(), e);
        }
    }

    private void writeTokenToFile(String token) throws IOException {
        final Path path = Path.of(tokenFilePath);

        final Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(path, token,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);

        try {
            final Set<PosixFilePermission> permissions =
                    PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException e) {
            log.debug("POSIX file permissions not supported on this OS, skipping chmod");
        }
    }

    private boolean isConfigured() {
        if (!enabled) {
            log.debug("Alertmanager token refresh is disabled");
            return false;
        }
        if (tokenFilePath == null || tokenFilePath.isBlank()) {
            log.debug("alertmanager.token-file-path not configured — " +
                    "Alertmanager token rotation disabled");
            return false;
        }
        return true;
    }
}