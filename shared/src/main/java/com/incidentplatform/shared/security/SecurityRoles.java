package com.incidentplatform.shared.security;

/**
 * Spring Security role string constants used across all services.
 *
 * <p>Spring Security uses a {@code ROLE_} prefix convention:
 * <ul>
 *   <li>{@code hasRole("ADMIN")} is equivalent to checking for authority {@code ROLE_ADMIN}
 *   <li>{@code hasAnyRole("SERVICE", "ADMIN")} checks for {@code ROLE_SERVICE} or {@code ROLE_ADMIN}
 * </ul>
 *
 * <p>This class provides both variants:
 * <ul>
 *   <li><b>Full form</b> ({@code ROLE_*}) — used in JWT token generation,
 *       {@code @PreAuthorize} SpEL expressions with {@code hasRole()},
 *       and anywhere a full authority string is needed.
 *   <li><b>Short form</b> ({@code *_NAME}) — used in {@code hasRole()} /
 *       {@code hasAnyRole()} where Spring adds the {@code ROLE_} prefix automatically.
 * </ul>
 */
public final class SecurityRoles {

    // ── Full form (ROLE_ prefix) — used in JWT claims and @PreAuthorize ────
    public static final String ROLE_ADMIN     = "ROLE_ADMIN";
    public static final String ROLE_RESPONDER = "ROLE_RESPONDER";
    public static final String ROLE_INGESTOR  = "ROLE_INGESTOR";
    public static final String ROLE_SERVICE   = "ROLE_SERVICE";

    // ── Short form (no prefix) — used in hasRole() / hasAnyRole() ──────────
    // Spring Security's hasRole("X") internally checks for authority "ROLE_X".
    public static final String ADMIN     = "ADMIN";
    public static final String RESPONDER = "RESPONDER";
    public static final String INGESTOR  = "INGESTOR";
    public static final String SERVICE   = "SERVICE";

    private SecurityRoles() {}
}