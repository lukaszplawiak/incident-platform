package com.incidentplatform.shared.observability;

import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Extends Spring Boot's default {@code http.server.requests} metric with a
 * {@code tenant} tag, enabling per-tenant request rate, latency and error
 * rate queries in Prometheus/Grafana — e.g. "p99 latency for tenant X" or
 * "which tenant has the highest 5xx rate".
 *
 * <h2>Why this and not a dedicated request-observability filter</h2>
 * Spring Boot already instruments every HTTP request via the built-in
 * {@code ServerHttpObservationFilter}, producing {@code http.server.requests}
 * with {@code method}, {@code uri}, {@code status}, {@code outcome} and
 * {@code exception} tags — with timing, histograms and percentiles handled
 * by Micrometer. Building a parallel filter to re-measure the same thing
 * (as {@code TenantKafkaRecordInterceptor} does for Kafka, where no
 * equivalent built-in instrumentation exists) would duplicate
 * infrastructure that already works correctly. Extending the existing
 * {@link DefaultServerRequestObservationConvention} is the idiomatic
 * Spring Boot 3.x way to add a custom dimension to an observation that
 * Spring already produces.
 *
 * <h2>Why a request attribute and not {@code TenantContext}/MDC directly</h2>
 * {@code ServerHttpObservationFilter} wraps the entire Spring Security
 * filter chain (including {@code JwtAuthFilter}) — its {@code stop()},
 * which is what triggers {@link #getLowCardinalityKeyValues}, runs only
 * after {@code JwtAuthFilter}'s {@code finally} block has already cleared
 * {@code TenantContext} (ThreadLocal) and MDC. By that point neither is
 * readable. {@link ServerRequestObservationContext#getCarrier()} returns
 * the {@code HttpServletRequest}, which survives the full request lifecycle
 * regardless of ThreadLocal cleanup — so the tenant is read from a request
 * attribute instead, set by {@code JwtAuthFilter} under
 * {@link TenantContext#REQUEST_ATTRIBUTE_TENANT_ID}.
 *
 * <p>That constant lives on {@code TenantContext} rather than on this class
 * so that neither this class nor {@code JwtAuthFilter} needs to import the
 * other — both reference the same shared constant instead, avoiding a
 * dependency from the security filter onto an unrelated observability
 * concern (the same pattern as
 * {@code SharedSecurityAutoConfiguration#PUBLIC_PATHS}).
 *
 * <h2>What this deliberately does NOT do</h2>
 * This only adds a metric tag — it does not add structured per-request
 * logging, business-level (domain) metrics, or distributed tracing. Unlike
 * Kafka consumption, where {@code TenantKafkaRecordInterceptor} closed a
 * real, observed gap (logs showing {@code [no-tenant]} for most of a
 * record's processing), no equivalent gap exists here: {@code JwtAuthFilter}
 * already populates MDC with {@code tenantId}/{@code userId}/{@code requestId}
 * before any controller or service code runs, so every application log line
 * during request processing already carries the correct context. Adding a
 * full request-observability filter on top would build infrastructure
 * without a concrete problem driving it — deferred until a specific need
 * (e.g. per-tenant business metrics, request payload auditing, distributed
 * tracing) justifies it.
 */
@Component
public class TenantServerRequestObservationConvention
        extends DefaultServerRequestObservationConvention {

    private static final String UNKNOWN_TENANT = "unknown";

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and(tenant(context));
    }

    private KeyValue tenant(ServerRequestObservationContext context) {
        final Object tenantId = context.getCarrier()
                .getAttribute(TenantContext.REQUEST_ATTRIBUTE_TENANT_ID);
        return KeyValue.of("tenant",
                tenantId != null ? tenantId.toString() : UNKNOWN_TENANT);
    }
}