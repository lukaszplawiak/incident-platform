package com.incidentplatform.incident.api;

import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.SecurityRoles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/dev/token")
@Profile({"local", "dev"})
@Tag(name = "Dev Tools", description = "LOCAL PROFILE ONLY")
public class DevTokenController {

    private static final Logger log = LoggerFactory.getLogger(DevTokenController.class);

    private final JwtUtils jwtUtils;
    private final Environment environment;

    public DevTokenController(JwtUtils jwtUtils,
                              Environment environment) {
        this.jwtUtils = jwtUtils;
        this.environment = environment;
    }

    /**
     * Fail-fast guard — second line of defence after {@code @Profile}.
     *
     * <p>If this bean is somehow instantiated outside the expected profiles
     * (e.g. misconfigured deployment with SPRING_PROFILES_ACTIVE=dev on prod),
     * the application will refuse to start rather than expose a token
     * generation endpoint without authentication.
     */
    @PostConstruct
    void validateNotProduction() {
        final java.util.List<String> activeProfiles =
                java.util.Arrays.asList(environment.getActiveProfiles());
        final boolean isDevOrLocal = activeProfiles.contains("local")
                || activeProfiles.contains("dev");

        if (!isDevOrLocal) {
            throw new IllegalStateException(
                    "DevTokenController must not run outside local/dev profile. " +
                            "Active profiles: " + activeProfiles + ". " +
                            "This is a critical security misconfiguration — " +
                            "set SPRING_PROFILES_ACTIVE=prod to fix."
            );
        }

        log.warn("DevTokenController is ACTIVE — profile(s): {}. " +
                "Token generation is available at /dev/token. " +
                "This must NOT run in production.", activeProfiles);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate test JWT token (LOCAL ONLY)")
    public ResponseEntity<Map<String, String>> generateToken(
            @RequestParam(defaultValue = "test-tenant") String tenantId,
            @RequestParam(defaultValue = SecurityRoles.ROLE_ADMIN) String role) {

        final UUID userId = UUID.randomUUID();
        final String email = "dev-user@" + tenantId + ".local";
        final List<String> roles = List.of(
                SecurityRoles.ROLE_ADMIN, SecurityRoles.ROLE_RESPONDER, SecurityRoles.ROLE_INGESTOR);

        final String token = jwtUtils.generateToken(userId, tenantId, email, roles);

        log.warn("DEV TOKEN generated: userId={}, tenantId={} — NOT FOR PRODUCTION",
                userId, tenantId);

        return ResponseEntity.ok(Map.of(
                "token", token,
                "userId", userId.toString(),
                "tenantId", tenantId,
                "email", email,
                "usage", "Authorization: Bearer " + token
        ));
    }
}