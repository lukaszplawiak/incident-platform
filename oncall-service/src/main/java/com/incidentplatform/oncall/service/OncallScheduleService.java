package com.incidentplatform.oncall.service;

import com.incidentplatform.oncall.domain.OncallRole;
import com.incidentplatform.oncall.domain.OncallSchedule;
import com.incidentplatform.oncall.dto.CreateOncallScheduleRequest;
import com.incidentplatform.oncall.dto.CurrentOncallResponse;
import com.incidentplatform.oncall.dto.OncallScheduleDto;
import com.incidentplatform.oncall.dto.SlackUserLookupResponse;
import com.incidentplatform.oncall.repository.OncallScheduleRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OncallScheduleService {

    private static final Logger log =
            LoggerFactory.getLogger(OncallScheduleService.class);

    private final OncallScheduleRepository repository;

    public OncallScheduleService(OncallScheduleRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public OncallScheduleDto create(String tenantId,
                                    CreateOncallScheduleRequest request) {
        final boolean overlapping = repository.existsOverlappingForCreate(
                tenantId,
                request.role(),
                request.startsAt(),
                request.endsAt()
        );

        if (overlapping) {
            throw BusinessException.scheduleOverlap(tenantId, request.role());
        }

        final OncallRole role;
        try {
            role = OncallRole.valueOf(request.role());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCodes.VALIDATION_FAILED,
                    String.format("Invalid on-call role '%s'. " +
                                    "Allowed values: PRIMARY, SECONDARY, MANAGER",
                            request.role()),
                    HttpStatus.BAD_REQUEST
            );
        }


        final OncallSchedule schedule = OncallSchedule.create(
                tenantId,
                request.userId(),
                request.userName(),
                request.email(),
                request.phone(),
                request.slackUserId(),
                role,
                request.startsAt(),
                request.endsAt(),
                request.notes()
        );

        repository.save(schedule);

        log.info("OncallSchedule created: tenantId={}, userId={}, " +
                        "role={}, startsAt={}, endsAt={}",
                tenantId, request.userId(), role,
                request.startsAt(), request.endsAt());

        return OncallScheduleDto.from(schedule);
    }

    @Transactional(readOnly = true)
    public Optional<CurrentOncallResponse> getCurrentOncall(
            String tenantId, String role) {
        return repository.findCurrentOncallByRole(tenantId, role, Instant.now())
                .map(CurrentOncallResponse::from);
    }

    @Transactional(readOnly = true)
    public List<CurrentOncallResponse> getAllCurrentOncall(String tenantId) {
        return repository.findAllCurrentOncall(tenantId, Instant.now())
                .stream()
                .map(CurrentOncallResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OncallScheduleDto> getSchedules(String tenantId) {
        return repository.findByTenantIdOrderByStartsAtDesc(tenantId)
                .stream()
                .map(OncallScheduleDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OncallScheduleDto getById(UUID id, String tenantId) {
        return repository.findByIdAndTenantId(id, tenantId)
                .map(OncallScheduleDto::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "OncallSchedule", id));
    }

    @Transactional(readOnly = true)
    public Optional<SlackUserLookupResponse> findBySlackUserId(
            String slackUserId) {
        return repository.findBySlackUserId(slackUserId)
                .stream()
                .findFirst()
                .map(schedule -> new SlackUserLookupResponse(
                        schedule.getUserId(),
                        schedule.getUserName(),
                        schedule.getTenantId(),
                        schedule.getSlackUserId()
                ));
    }

    @Transactional
    public void delete(UUID id, String tenantId) {
        final OncallSchedule schedule = repository
                .findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "OncallSchedule", id));

        repository.delete(schedule);

        log.info("OncallSchedule deleted: id={}, tenantId={}", id, tenantId);
    }
}