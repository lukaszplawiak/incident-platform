package com.incidentplatform.shared.observability;

import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * Note on stubbing: {@code DefaultServerRequestObservationConvention}
 * (the superclass) internally calls {@code request.getMethod()} and
 * {@code response.getStatus()} to build the default {@code method}/{@code status}
 * tags — these must be stubbed in every test that exercises
 * {@code getLowCardinalityKeyValues()}, even though this test suite isn't
 * directly asserting on those tags, otherwise Mockito's default {@code null}/
 * {@code 0} return values cause the superclass to throw.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TenantServerRequestObservationConvention")
class TenantServerRequestObservationConventionTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private TenantServerRequestObservationConvention convention;

    @BeforeEach
    void setUp() {
        convention = new TenantServerRequestObservationConvention();

        // Required by the superclass (DefaultServerRequestObservationConvention)
        // for every call to getLowCardinalityKeyValues() — see class Javadoc.
        lenient().when(request.getMethod()).thenReturn("GET");
        lenient().when(response.getStatus()).thenReturn(200);
    }

    private ServerRequestObservationContext buildContext() {
        return new ServerRequestObservationContext(request, response);
    }

    @Nested
    @DisplayName("tenant tag")
    class TenantTag {

        @Test
        @DisplayName("uses the tenantId set as a request attribute by JwtAuthFilter")
        void shouldUseTenantIdFromRequestAttribute() {
            // given
            given(request.getAttribute(TenantContext.REQUEST_ATTRIBUTE_TENANT_ID))
                    .willReturn("acme-corp");

            final ServerRequestObservationContext context = buildContext();

            // when
            final KeyValues result = convention.getLowCardinalityKeyValues(context);

            // then
            assertThat(findTag(result, "tenant"))
                    .map(KeyValue::getValue)
                    .contains("acme-corp");
        }

        @Test
        @DisplayName("falls back to 'unknown' when no tenant attribute is present " +
                "(public path, unauthenticated request)")
        void shouldFallBackToUnknownWhenAttributeAbsent() {
            // given — request.getAttribute() returns null by default (no stub needed)
            final ServerRequestObservationContext context = buildContext();

            // when
            final KeyValues result = convention.getLowCardinalityKeyValues(context);

            // then — must not be null, must not throw; "unknown" keeps the tag's
            // cardinality bounded and queryable rather than producing a missing
            // series in Prometheus
            assertThat(findTag(result, "tenant"))
                    .map(KeyValue::getValue)
                    .contains("unknown");
        }

        @Test
        @DisplayName("does not throw when the request attribute is a non-String object")
        void shouldHandleNonStringAttributeGracefully() {
            // given — defensive case: nothing currently sets a non-String value
            // here, but getAttribute() is typed Object, so a future caller could
            given(request.getAttribute(TenantContext.REQUEST_ATTRIBUTE_TENANT_ID))
                    .willReturn(12345);

            final ServerRequestObservationContext context = buildContext();

            // when / then
            assertThat(findTag(convention.getLowCardinalityKeyValues(context), "tenant"))
                    .map(KeyValue::getValue)
                    .contains("12345");
        }
    }

    @Nested
    @DisplayName("default tags preserved")
    class DefaultTagsPreserved {

        @Test
        @DisplayName("adds the tenant tag alongside the default tags, not instead of them")
        void shouldKeepDefaultTagsAlongsideTenant() {
            // Regression guard for a realistic mistake: overriding
            // getLowCardinalityKeyValues() without calling super.*() and
            // .and(...) would silently drop method/status/exception/outcome
            // tags that every other http.server.requests consumer relies on.
            given(request.getAttribute(TenantContext.REQUEST_ATTRIBUTE_TENANT_ID))
                    .willReturn("acme-corp");

            final ServerRequestObservationContext context = buildContext();

            final KeyValues result = convention.getLowCardinalityKeyValues(context);

            assertThat(findTag(result, "method"))
                    .as("default 'method' tag must still be present")
                    .isPresent();
            assertThat(findTag(result, "tenant"))
                    .as("custom 'tenant' tag must also be present")
                    .isPresent();
        }
    }

    private java.util.Optional<KeyValue> findTag(KeyValues keyValues, String key) {
        return keyValues.stream()
                .filter(kv -> kv.getKey().equals(key))
                .findFirst();
    }
}