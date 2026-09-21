package com.incidentplatform.shared.security;

/**
 * Names of the services that accept service tokens, used as the {@code aud}
 * (audience) claim of a service token.
 *
 * <h2>Added (backlog #0-11): a service token is valid for one service</h2>
 * A service token used to be accepted by every service that shares the JWT
 * secret, so a token minted to call oncall-service also authenticated on
 * auth-service. {@link JwtAuthFilter} now accepts a service token only when
 * its {@code aud} names <em>this</em> service, so each of these values must
 * equal the target's {@code spring.application.name}. A caller passes the
 * name of the service it is calling — never its own.
 */
public final class ServiceNames {

    public static final String ONCALL_SERVICE    = "oncall-service";
    public static final String INCIDENT_SERVICE  = "incident-service";
    public static final String INGESTION_SERVICE = "ingestion-service";

    private ServiceNames() {
    }
}
