package com.incidentplatform.incident.service;

import com.incidentplatform.incident.dto.IncidentDto;
import com.incidentplatform.incident.dto.IncidentFilter;
import com.incidentplatform.incident.dto.IncidentHistoryDto;
import com.incidentplatform.incident.repository.IncidentHistoryRepository;
import com.incidentplatform.incident.repository.IncidentRepository;
import com.incidentplatform.incident.repository.IncidentSpecification;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class IncidentQueryService {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentQueryService.class);

    private final IncidentRepository incidentRepository;
    private final IncidentHistoryRepository historyRepository;

    public IncidentQueryService(IncidentRepository incidentRepository,
                                IncidentHistoryRepository historyRepository) {
        this.incidentRepository = incidentRepository;
        this.historyRepository = historyRepository;
    }

    public Page<IncidentDto> findAll(String tenantId,
                                     IncidentFilter filter,
                                     Pageable pageable) {
        log.debug("Finding incidents: tenantId={}, filter={}, pageable={}",
                tenantId, filter, pageable);

        final Page<IncidentDto> result;

        if (!filter.hasAnyFilter()) {
            result = incidentRepository
                    .findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                    .map(IncidentDto::from);
        } else {
            result = incidentRepository
                    .findAll(
                            IncidentSpecification.withFilter(tenantId, filter),
                            pageable)
                    .map(IncidentDto::from);
        }

        log.debug("Found {} incidents on page {}/{}: tenantId={}",
                result.getNumberOfElements(),
                result.getNumber(),
                result.getTotalPages(),
                tenantId);

        return result;
    }

    public IncidentDto findById(UUID incidentId, String tenantId) {
        log.debug("Finding incident: incidentId={}, tenantId={}",
                incidentId, tenantId);

        return incidentRepository
                .findByIdAndTenantId(incidentId, tenantId)
                .map(IncidentDto::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Incident", incidentId));
    }

    public List<IncidentHistoryDto> findHistory(UUID incidentId,
                                                String tenantId) {
        log.debug("Finding incident history: incidentId={}, tenantId={}",
                incidentId, tenantId);

        if (!incidentRepository.existsById(incidentId)) {
            throw new ResourceNotFoundException("Incident", incidentId);
        }

        return historyRepository
                .findByIncidentIdAndTenantIdOrderByChangedAtAsc(
                        incidentId, tenantId)
                .stream()
                .map(IncidentHistoryDto::from)
                .toList();
    }
}