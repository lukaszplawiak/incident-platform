package com.incidentplatform.ingestion.alertmanager;

import com.incidentplatform.shared.security.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

@Component
public class AlertManagerTokenRefresher {

    private static final Logger log =
            LoggerFactory.getLogger(AlertManagerTokenRefresher.class);

    private final JwtUtils jwtUtils;
    private final String tokenFilePath;
    private final long expirationMs;
    private final boolean enabled;

    private static final String SERVICE_NAME = "alertmanager";

    public AlertManagerTokenRefresher(
            JwtUtils jwtUtils,
            @Value("${alertmanager.token-file-path:}") String tokenFilePath,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs,
            @Value("${alertmanager.token-refresh-enabled:true}") boolean enabled) {
        this.jwtUtils = jwtUtils;
        this.tokenFilePath = tokenFilePath;
        this.expirationMs = expirationMs;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void generateTokenOnStartup() {
        if (!isConfigured()) return;
        log.info("Generating initial Alertmanager ingestor token on startup: file={}",
                tokenFilePath);
        refreshToken();
    }

    @Scheduled(fixedDelayString = "${alertmanager.token-refresh-delay-ms:#{${jwt.expiration-ms:86400000} * 8 / 10}}")
    public void refreshToken() {
        if (!isConfigured()) return;

        try {
            final String token = jwtUtils.generateServiceToken(SERVICE_NAME);
            writeTokenToFile(token);

            log.info("Alertmanager ingestor token refreshed: file={}, " +
                            "expiresInMs={}, nextRefreshInMs={}",
                    tokenFilePath,
                    expirationMs,
                    (long) (expirationMs * 0.8));

        } catch (Exception e) {
            log.error("Failed to refresh Alertmanager ingestor token: file={}, error={}",
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
                    "Alertmanager token refresh disabled");
            return false;
        }
        return true;
    }
}