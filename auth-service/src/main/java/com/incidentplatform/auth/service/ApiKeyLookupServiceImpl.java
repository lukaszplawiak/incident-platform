package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.ApiKey;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.ApiKeyAuthFilter.ApiKeyLookupResult;
import jakarta.servlet.http.HttpServletRequest;
import com.incidentplatform.shared.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * DB-backed implementation of {@link ApiKeyAuthFilter.ApiKeyLookupService}
 * for API keys sent to auth-service itself.
 *
 * <p>Called by {@link ApiKeyAuthFilter} on every API key request. Whether a
 * key is active, and which team it routes to, is decided by
 * {@link ApiKeyIntrospectionService#resolve} — the same code path that answers
 * ingestion-service's introspection calls (backlog #0-16), so the two cannot
 * disagree. This class only turns the result into a {@link UserPrincipal}.
 *
 * <h2>Principal construction</h2>
 * <ul>
 *   <li><b>TENANT key</b> — principal has {@code ROLE_RESPONDER} and
 *       granted scopes. {@code userId} is the API key's own ID (there is
 *       no real human user behind a tenant key) — see
 *       {@link ApiKeyService#createApiKey} for the separate, ADMIN-only
 *       restriction on who may create a TENANT key in the first place.</li>
 *   <li><b>PERSONAL key</b> — principal inherits owner's roles and
 *       has granted scopes. userId = owner's UUID.</li>
 * </ul>
 *
 * <h2>Usage recording</h2>
 * Done inside {@link ApiKeyIntrospectionService#resolve} by
 * {@link ApiKeyUsageRecorder}, throttled and best-effort.
 */
@Service
public class ApiKeyLookupServiceImpl
        implements ApiKeyAuthFilter.ApiKeyLookupService {

    private final ApiKeyIntrospectionService introspectionService;
    private final ApiKeyHasher apiKeyHasher;

    public ApiKeyLookupServiceImpl(ApiKeyIntrospectionService introspectionService,
                                   ApiKeyHasher apiKeyHasher) {
        this.introspectionService = introspectionService;
        this.apiKeyHasher         = apiKeyHasher;
    }

    /**
     * Never {@code Unavailable} or {@code Throttled}: the table is local, and a
     * database outage fails the request like any other query would.
     */
    @Override
    @Transactional(readOnly = true)
    public ApiKeyLookupResult lookup(String rawKey, HttpServletRequest request) {
        return introspectionService.resolve(apiKeyHasher.hash(rawKey))
                .<ApiKeyLookupResult>map(active -> new ApiKeyLookupResult.Authenticated(
                        buildPrincipal(active.apiKey(), active.teamId())))
                .orElseGet(ApiKeyLookupResult.Invalid::new);
    }

    private UserPrincipal buildPrincipal(ApiKey apiKey, UUID teamId) {
        final List<String> roles;
        final UUID userId;

        if (apiKey.isTenant()) {
            roles  = List.of("ROLE_RESPONDER");
            userId = apiKey.getId();
        } else {
            roles  = apiKey.getOwnerUser().getRoleNames();
            userId = apiKey.getOwnerUser().getId();
        }

        // teamId is stored in teamIds list — ingestion-service reads
        // principal.teamIds().get(0) to set UnifiedAlertDto.teamId
        final List<UUID> teamIds = teamId != null
                ? List.of(teamId) : List.of();

        return new UserPrincipal(
                userId,
                apiKey.getTenantId(),
                apiKey.isTenant()
                        ? "api-key:" + apiKey.getName()
                        : apiKey.getOwnerUser().getEmail(),
                roles,
                teamIds,
                List.of(), // managedTeamIds — not applicable to API key principals,
                // same reasoning as teamIds/scopes for machine-to-machine calls
                true,
                apiKey.getScopes(),
                null // sessionId — not applicable; API keys authenticate
                // machine-to-machine calls, not a human login session
        );
    }
}
