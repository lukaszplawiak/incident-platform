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
    // Removed (backlog #0-16): INGESTION_SERVICE. Its only caller was the
    // platform Alertmanager's service token; ingestion-service now accepts no
    // service tokens and authenticates alert sources by Integration API key.

    /**
     * Added (backlog #0-21/#0-30): auth-service was deliberately never a valid
     * service-token audience — its {@link JwtAuthFilter} was wired with no
     * {@code expectedAudience}, so it rejected every service token (see that
     * class's own Javadoc history). This is the one narrow exception: a
     * tenant's Slack workspace connection lives in auth-service and
     * notification-service needs to read it, so auth-service now accepts a
     * service token minted for exactly this audience, on exactly one
     * internal endpoint. Not a general reopening — see backlog #0-30 for the
     * decision and its alternative (Kafka event replication).
     */
    public static final String AUTH_SERVICE = "auth-service";

    private ServiceNames() {
    }
}
