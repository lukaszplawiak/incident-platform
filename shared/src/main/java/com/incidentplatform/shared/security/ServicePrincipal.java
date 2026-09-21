package com.incidentplatform.shared.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

/**
 * Authenticated principal for a service-to-service call, built by
 * {@link JwtAuthFilter} from a service token (see
 * {@link JwtUtils#generateServiceToken}).
 *
 * <h2>Fixed (backlog #0-11): why this is not a {@link UserPrincipal}</h2>
 * Service tokens used to go through the same branch of
 * {@link JwtAuthFilter} as user tokens, which requires a UUID
 * {@code sub}, an {@code email} claim and a {@code tenantId}. A service
 * token has the service name as {@code sub} and no email, so the filter
 * rejected every one of them and each service-to-service HTTP call ended
 * as a 401 — hidden by the fail-open fallbacks in the clients. Instead of
 * inventing a fake user id and email to satisfy that shape, a service gets
 * its own principal type that says only what is true about it: which
 * service is calling, and for which tenant.
 *
 * <p>{@link #tenantId} is the tenant the caller asked for, carried in the
 * signed {@code tenantId} claim of the token
 * ({@link ServiceTokenProvider#getToken(String)} mints one token per
 * tenant). It is never taken from a request header.
 *
 * <p>Controllers that read {@code @AuthenticationPrincipal UserPrincipal}
 * receive {@code null} for a service call (Spring does not inject a
 * principal of an incompatible type), so an endpoint that is callable with
 * {@code ROLE_SERVICE} must not dereference it — see
 * {@code AlertIngestionController} for the null-safe pattern.
 *
 * @param serviceName name of the calling service (the {@code serviceName} claim)
 * @param tenantId    tenant this call acts for (the {@code tenantId} claim)
 */
public record ServicePrincipal(String serviceName, String tenantId)
        implements Principal {

    public ServicePrincipal {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }

    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(SecurityRoles.ROLE_SERVICE));
    }

    @Override
    public String getName() {
        return serviceName;
    }
}
