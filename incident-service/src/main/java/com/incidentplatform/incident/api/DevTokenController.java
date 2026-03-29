package com.incidentplatform.incident.api;

import com.incidentplatform.shared.security.JwtUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
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
@Profile("local")
@Tag(name = "Dev Tools", description = "LOCAL PROFILE ONLY")
public class DevTokenController {

    private static final Logger log = LoggerFactory.getLogger(DevTokenController.class);

    private final JwtUtils jwtUtils;

    public DevTokenController(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate test JWT token (LOCAL ONLY)")
    public ResponseEntity<Map<String, String>> generateToken(
            @RequestParam(defaultValue = "test-tenant") String tenantId,
            @RequestParam(defaultValue = "ROLE_ADMIN") String role) {

        final UUID userId = UUID.randomUUID();
        final String email = "dev-user@" + tenantId + ".local";
        final List<String> roles = List.of(
                "ROLE_ADMIN", "ROLE_RESPONDER", "ROLE_INGESTOR");

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