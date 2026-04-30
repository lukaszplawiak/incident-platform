package com.incidentplatform.incident.repository;

import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.incident.dto.IncidentFilter;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class IncidentSpecification {

    private IncidentSpecification() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Specification<Incident> withFilter(String tenantId,
                                                     IncidentFilter filter) {
        return (root, query, criteriaBuilder) -> {
            final List<Predicate> predicates = new ArrayList<>();

            predicates.add(criteriaBuilder.equal(
                    root.get("tenantId"), tenantId));

            if (filter.status() != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("status"), filter.status()));
            }

            if (filter.severity() != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("severity"), filter.severity()));
            }

            if (filter.sourceType() != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("sourceType"), filter.sourceType()));
            }

            if (filter.source() != null && !filter.source().isBlank()) {
                predicates.add(criteriaBuilder.equal(
                        root.get("source"),
                        filter.source().toLowerCase()));
            }

            if (query != null && query.getResultType() != Long.class) {
                query.orderBy(criteriaBuilder.desc(root.get("createdAt")));
            }

            return criteriaBuilder.and(
                    predicates.toArray(new Predicate[0]));
        };
    }

    public static Specification<Incident> active() {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.notEqual(
                        root.get("status"), IncidentStatus.CLOSED);
    }
}