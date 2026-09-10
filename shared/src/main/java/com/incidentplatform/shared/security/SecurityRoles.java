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
 *   <li><b>Short form</b> ({@code *_NAME}, no prefix) — the standard,
 *       forward-compatible choice for {@code hasRole()}/{@code hasAnyRole()}
 *       everywhere they appear: {@code @PreAuthorize} SpEL expressions AND
 *       URL-level {@code authorizeHttpRequests}/{@code SecurityFilterChain}
 *       rules. Spring adds the {@code ROLE_} prefix automatically — that is
 *       the entire reason {@code hasRole()} exists as a method distinct from
 *       {@code hasAuthority()} (see Spring Security's own 4.0 release notes:
 *       "since the expression hasRole already defines the value as a role it
 *       automatically adds the prefix... We do this to remove duplication").
 *       Passing the prefix explicitly is tolerated today only as a passivity
 *       accommodation — Spring Security's own tracked issue #17783
 *       (spring-projects/spring-security) documents that this tolerance is
 *       removed in Spring Security 7, where {@code hasRole("ROLE_ADMIN")}
 *       throws {@code IllegalArgumentException} rather than silently working.
 *       Previously, this project used both forms inconsistently across
 *       controllers (a mix of {@code hasRole('ADMIN')} and
 *       {@code hasRole('ROLE_ADMIN')}) partly because this very class's own
 *       Javadoc contradicted itself on which form to use where — fixed here,
 *       and every {@code @PreAuthorize} in the codebase unified on this
 *       short form accordingly.
 *   <li><b>Full form</b> ({@code ROLE_*}, prefixed) — used anywhere a
 *       complete, literal authority string is required rather than compared
 *       against a role name Spring itself prefixes: JWT token generation
 *       ({@code JwtUtils}, embedding the {@code roles} claim), the JWT claim
 *       values themselves as read back out ({@code user.getRoleNames()}),
 *       and {@link UserPrincipal#hasRole(String)} — a plain
 *       {@code List.contains(String)} check against those same full-form
 *       role strings, entirely unrelated to Spring Security's own
 *       {@code hasRole()}/{@code hasAnyRole()} despite the shared method
 *       name, and therefore requiring the full {@code ROLE_}-prefixed
 *       string to ever match.
 * </ul>
 */
public final class SecurityRoles {

    // ── Full form (ROLE_ prefix) ─────────────────────────────────────────
    // JWT claims/token generation, and UserPrincipal.hasRole(String) — a
    // plain List.contains(String) check, NOT Spring Security's hasRole().
    public static final String ROLE_ADMIN     = "ROLE_ADMIN";
    public static final String ROLE_RESPONDER = "ROLE_RESPONDER";
    public static final String ROLE_INGESTOR  = "ROLE_INGESTOR";
    public static final String ROLE_SERVICE   = "ROLE_SERVICE";

    // ── Short form (no prefix) ───────────────────────────────────────────
    // The standard choice for Spring Security's own hasRole()/hasAnyRole(),
    // in @PreAuthorize SpEL and in SecurityFilterChain/authorizeHttpRequests
    // alike — Spring adds the ROLE_ prefix automatically. See this class's
    // own Javadoc above for the full account of why, and why the ROLE_
    // -prefixed form must never be used here even though it happens to
    // still work today.
    public static final String ADMIN     = "ADMIN";
    public static final String RESPONDER = "RESPONDER";
    public static final String INGESTOR  = "INGESTOR";
    public static final String SERVICE   = "SERVICE";

    private SecurityRoles() {}
}