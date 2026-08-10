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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * Fixed: role conversion/validation ({@code OncallRole.valueOf(...)})
     * previously happened AFTER calling {@link
     * OncallScheduleRepository#existsOverlappingForCreate}, which itself
     * was called with the raw {@code String} from the request. That
     * repository method's {@code role} parameter needs an
     * {@code OncallRole} (see its own Javadoc for the full account of why
     * this was a genuine, live production bug — every call to this method
     * would have thrown). Moved the conversion above the overlap check so
     * the repository is always called with a real {@code OncallRole}, and
     * so an invalid role string is rejected with the existing clean
     * {@code VALIDATION_FAILED} response before any database work happens
     * at all — the more correct order regardless of the type-mismatch fix.
     */
    @Transactional
    public OncallScheduleDto create(String tenantId,
                                    CreateOncallScheduleRequest request) {
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

        final boolean overlapping = repository.existsOverlappingForCreate(
                tenantId,
                request.teamId(),
                role,
                request.startsAt(),
                request.endsAt()
        );

        if (overlapping) {
            throw BusinessException.scheduleOverlap(tenantId, request.role());
        }

        final OncallSchedule schedule = OncallSchedule.create(
                tenantId,
                request.teamId(),
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

        // Fixed: existsOverlappingForCreate(...) above is a check-then-act —
        // two concurrent requests for the same tenant+team+role with
        // overlapping windows could both pass that check (neither has
        // committed yet) and both reach save(). The excl_oncall_schedule_overlap
        // constraint (V4 migration) makes the database itself the real
        // guarantee: it will reject the second insert regardless of
        // application-layer timing. Without this catch, that rejection would
        // have surfaced as a raw, unhandled DataIntegrityViolationException —
        // effectively a 500 — instead of the same clean 409 Conflict the
        // (much more commonly hit) application-level check above already
        // produces for this identical business situation.
        try {
            repository.save(schedule);
        } catch (DataIntegrityViolationException e) {
            log.warn("Schedule overlap caught by DB constraint (race condition " +
                            "past the application-level check): tenantId={}, " +
                            "teamId={}, role={}",
                    tenantId, request.teamId(), request.role());
            throw BusinessException.scheduleOverlap(tenantId, request.role());
        }

        log.info("OncallSchedule created: tenantId={}, userId={}, " +
                        "role={}, startsAt={}, endsAt={}",
                tenantId, request.userId(), role,
                request.startsAt(), request.endsAt());

        return OncallScheduleDto.from(schedule);
    }

    /**
     * Fixed: previously passed the raw {@code role} String straight to
     * {@link OncallScheduleRepository#findCurrentOncallByRole}, which
     * needs an {@code OncallRole} (see that method's Javadoc for the full
     * account — this was a live production bug affecting one of the two
     * most-used endpoints in this service).
     *
     * <p>An unparseable {@code role} is treated the same as "no schedule
     * found" ({@code Optional.empty()}) rather than thrown as a validation
     * error: this is a read/lookup path, called by notification-service
     * and escalation-service behind their own circuit breakers — those
     * callers always pass a fixed, valid role string in practice, so a
     * malformed value reaching here is a defensive edge case, not a
     * realistic normal-operation one, and failing softly (no on-call
     * found) is safer than surfacing a new 5xx failure mode to
     * circuit-breaker-wrapped callers that don't distinguish "bad input"
     * from "service unavailable".
     */
    @Transactional(readOnly = true)
    public Optional<CurrentOncallResponse> getCurrentOncall(
            String tenantId, String role) {
        return parseRole(role)
                .flatMap(parsedRole -> repository.findCurrentOncallByRole(
                        tenantId, parsedRole, Instant.now()))
                .map(CurrentOncallResponse::from);
    }

    /**
     * Returns the current on-call person for a specific team and role.
     * Primary query used by EscalationScheduler via HTTP.
     *
     * <p>Fixed: same {@code String}-to-{@code OncallRole} conversion fix
     * and same "unparseable role treated as not-found" reasoning as
     * {@link #getCurrentOncall} — see its Javadoc.
     *
     * @return empty when no active schedule found for this team/role
     */
    @Transactional(readOnly = true)
    public Optional<CurrentOncallResponse> getCurrentOncallForTeam(
            String tenantId, UUID teamId, String role) {
        return parseRole(role)
                .flatMap(parsedRole -> repository.findCurrentOncallByTeamAndRole(
                        tenantId, teamId, parsedRole, Instant.now()))
                .map(CurrentOncallResponse::from);
    }

    private static Optional<OncallRole> parseRole(String role) {
        try {
            return Optional.of(OncallRole.valueOf(role));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<CurrentOncallResponse> getAllCurrentOncallForTeam(
            String tenantId, UUID teamId) {
        return repository.findAllCurrentOncallForTeam(
                        tenantId, teamId, Instant.now())
                .stream()
                .map(CurrentOncallResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CurrentOncallResponse> getAllCurrentOncall(String tenantId) {
        return repository.findAllCurrentOncall(tenantId, Instant.now())
                .stream()
                .map(CurrentOncallResponse::from)
                .toList();
    }

    /**
     * Returns a paginated list of all schedules for the given tenant.
     * Previously returned an unbounded {@code List} — replaced with
     * {@code Page} to avoid loading all rows into memory for tenants
     * with years of scheduling history.
     */
    @Transactional(readOnly = true)
    public Page<OncallScheduleDto> getSchedules(String tenantId,
                                                Pageable pageable) {
        return repository.findByTenantIdOrderByStartsAtDesc(tenantId, pageable)
                .map(OncallScheduleDto::from);
    }

    @Transactional(readOnly = true)
    public OncallScheduleDto getById(UUID id, String tenantId) {
        return repository.findByIdAndTenantId(id, tenantId)
                .map(OncallScheduleDto::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "OncallSchedule", id));
    }

    /**
     * Looks up the on-call schedule entry for a given Slack user within
     * the specified tenant. Used by {@code notification-service} when
     * resolving a Slack ACK button click to an internal system user ID.
     *
     * <p>The {@code tenantId} parameter is mandatory — without it the query
     * would search all tenants, and two tenants sharing the same Slack
     * workspace could have colliding {@code slackUserId} values.
     */
    @Transactional(readOnly = true)
    public Optional<SlackUserLookupResponse> findBySlackUserId(
            String tenantId, String slackUserId) {
        return repository.findByTenantIdAndSlackUserId(tenantId, slackUserId)
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