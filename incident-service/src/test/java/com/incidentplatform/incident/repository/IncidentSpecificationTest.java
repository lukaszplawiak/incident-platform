package com.incidentplatform.incident.repository;

import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.incident.dto.IncidentFilter;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.SourceType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentSpecification")
class IncidentSpecificationTest {

    @Mock
    private Root<Incident> root;

    @Mock
    private CriteriaQuery<?> query;

    @Mock
    private CriteriaBuilder criteriaBuilder;

    @Mock
    private Path<String> sourcePath;

    @Mock
    private Path<Object> fallbackPath;

    @Mock
    private Expression<String> lowerSourceExpression;

    @Mock
    private Predicate predicate;

    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        // lenient fallback stubs — IncidentSpecification always calls
        // root.get("tenantId") and criteriaBuilder.and()/equal() regardless of
        // which filters are active. Individual tests stub only the column(s) they
        // care about; without these lenient fallbacks, strict Mockito rejects
        // any "unexpected" invocation (e.g. root.get("tenantId") in a test that
        // only stubs root.get("source")).
        lenient().when(root.get(anyString())).thenReturn(fallbackPath);
        lenient().when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(predicate);
        lenient().when(criteriaBuilder.equal(any(), any())).thenReturn(predicate);
    }

    @Nested
    @DisplayName("source filter")
    class SourceFilter {

        @Test
        @DisplayName("should push LOWER() to database when source filter is present")
        void shouldUseLowerForSourceFilter() {
            // given — override the fallback for "source" with a typed Path<String>
            // so criteriaBuilder.lower(Path<String>) compiles and Mockito can match it
            given(root.<String>get("source")).willReturn(sourcePath);
            given(criteriaBuilder.lower(sourcePath)).willReturn(lowerSourceExpression);

            final Specification<Incident> spec =
                    IncidentSpecification.withFilter(TENANT_ID,
                            new IncidentFilter(null, null, null, "prometheus"));

            // when
            spec.toPredicate(root, query, criteriaBuilder);

            // then — LOWER() pushed to SQL, not just Java-side toLowerCase()
            then(criteriaBuilder).should().lower(sourcePath);
            then(criteriaBuilder).should().equal(lowerSourceExpression, "prometheus");
        }

        @Test
        @DisplayName("should normalise uppercase filter parameter to lowercase")
        void shouldLowercaseFilterParameter() {
            // given
            given(root.<String>get("source")).willReturn(sourcePath);
            given(criteriaBuilder.lower(sourcePath)).willReturn(lowerSourceExpression);

            final Specification<Incident> spec =
                    IncidentSpecification.withFilter(TENANT_ID,
                            new IncidentFilter(null, null, null, "PROMETHEUS"));

            // when
            spec.toPredicate(root, query, criteriaBuilder);

            // then — "PROMETHEUS" normalised to "prometheus" before SQL comparison
            then(criteriaBuilder).should().equal(lowerSourceExpression, "prometheus");
        }

        @Test
        @DisplayName("should treat mixed-case filter parameter as case-insensitive")
        void shouldBeCaseInsensitiveForMixedCase() {
            // given
            given(root.<String>get("source")).willReturn(sourcePath);
            given(criteriaBuilder.lower(sourcePath)).willReturn(lowerSourceExpression);

            final Specification<Incident> spec =
                    IncidentSpecification.withFilter(TENANT_ID,
                            new IncidentFilter(null, null, null, "Prometheus"));

            // when
            spec.toPredicate(root, query, criteriaBuilder);

            // then — "Prometheus" → "prometheus"
            then(criteriaBuilder).should().equal(lowerSourceExpression, "prometheus");
        }

        @Test
        @DisplayName("should not add source predicate when source filter is null")
        void shouldSkipSourcePredicateWhenNull() {
            // given
            final Specification<Incident> spec =
                    IncidentSpecification.withFilter(TENANT_ID,
                            new IncidentFilter(null, null, null, null));

            // when
            spec.toPredicate(root, query, criteriaBuilder);

            // then — lower() never called, no source predicate built
            then(criteriaBuilder).should(never()).lower(any());
        }

        @Test
        @DisplayName("should not add source predicate when source filter is blank")
        void shouldSkipSourcePredicateWhenBlank() {
            // given
            final Specification<Incident> spec =
                    IncidentSpecification.withFilter(TENANT_ID,
                            new IncidentFilter(null, null, null, "   "));

            // when
            spec.toPredicate(root, query, criteriaBuilder);

            // then
            then(criteriaBuilder).should(never()).lower(any());
        }
    }

    @Nested
    @DisplayName("other filters")
    class OtherFilters {

        @Test
        @DisplayName("should add status predicate when status filter is present")
        void shouldAddStatusPredicate() {
            // given — fallback stub returns fallbackPath for all root.get() calls
            // including "tenantId"; only verify the "status" predicate is built
            final Specification<Incident> spec =
                    IncidentSpecification.withFilter(TENANT_ID,
                            new IncidentFilter(IncidentStatus.OPEN, null, null, null));

            // when
            spec.toPredicate(root, query, criteriaBuilder);

            // then
            then(criteriaBuilder).should().equal(fallbackPath, IncidentStatus.OPEN);
        }

        @Test
        @DisplayName("should add severity predicate when severity filter is present")
        void shouldAddSeverityPredicate() {
            // given
            final Specification<Incident> spec =
                    IncidentSpecification.withFilter(TENANT_ID,
                            new IncidentFilter(null, Severity.CRITICAL, null, null));

            // when
            spec.toPredicate(root, query, criteriaBuilder);

            // then
            then(criteriaBuilder).should().equal(fallbackPath, Severity.CRITICAL);
        }

        @Test
        @DisplayName("should add sourceType predicate when sourceType filter is present")
        void shouldAddSourceTypePredicate() {
            // given
            final Specification<Incident> spec =
                    IncidentSpecification.withFilter(TENANT_ID,
                            new IncidentFilter(null, null, SourceType.OPS, null));

            // when
            spec.toPredicate(root, query, criteriaBuilder);

            // then
            then(criteriaBuilder).should().equal(fallbackPath, SourceType.OPS);
        }

        @Test
        @DisplayName("should always add tenant predicate regardless of other filters")
        void shouldAlwaysAddTenantPredicate() {
            // given
            final Specification<Incident> spec =
                    IncidentSpecification.withFilter(TENANT_ID,
                            new IncidentFilter(null, null, null, null));

            // when
            spec.toPredicate(root, query, criteriaBuilder);

            // then — tenantId predicate always present, regardless of filter content
            then(criteriaBuilder).should().equal(fallbackPath, TENANT_ID);
        }
    }
}