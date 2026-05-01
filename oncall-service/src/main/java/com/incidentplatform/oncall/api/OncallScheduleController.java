package com.incidentplatform.oncall.api;

import com.incidentplatform.oncall.dto.CreateOncallScheduleRequest;
import com.incidentplatform.oncall.dto.CurrentOncallResponse;
import com.incidentplatform.oncall.dto.OncallScheduleDto;
import com.incidentplatform.oncall.service.OncallScheduleService;
import com.incidentplatform.shared.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/oncall")
public class OncallScheduleController {

    private static final Logger log =
            LoggerFactory.getLogger(OncallScheduleController.class);

    private final OncallScheduleService service;

    public OncallScheduleController(OncallScheduleService service) {
        this.service = service;
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrentOncall(
            @NotBlank(message = "Role must not be blank if provided")
            @RequestParam(required = false) String role) {
        final String tenantId = TenantContext.getRequired();

        if (role != null) {
            log.debug("GET /api/v1/oncall/current?role={}, tenant={}",
                    role, tenantId);
            return service.getCurrentOncall(tenantId, role)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.noContent().build());
        }

        log.debug("GET /api/v1/oncall/current, tenant={}", tenantId);
        final List<CurrentOncallResponse> current =
                service.getAllCurrentOncall(tenantId);

        if (current.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(current);
    }

    @GetMapping("/schedules")
    public ResponseEntity<List<OncallScheduleDto>> getSchedules() {
        final String tenantId = TenantContext.getRequired();
        log.debug("GET /api/v1/oncall/schedules, tenant={}", tenantId);
        return ResponseEntity.ok(service.getSchedules(tenantId));
    }

    @GetMapping("/schedules/{id}")
    public ResponseEntity<OncallScheduleDto> getById(
            @PathVariable UUID id) {
        final String tenantId = TenantContext.getRequired();
        log.debug("GET /api/v1/oncall/schedules/{}, tenant={}", id, tenantId);
        return ResponseEntity.ok(service.getById(id, tenantId));
    }

    @PostMapping("/schedules")
    public ResponseEntity<OncallScheduleDto> create(
            @Valid @RequestBody CreateOncallScheduleRequest request) {
        final String tenantId = TenantContext.getRequired();
        log.debug("POST /api/v1/oncall/schedules, tenant={}, role={}",
                tenantId, request.role());

        final OncallScheduleDto created = service.create(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        final String tenantId = TenantContext.getRequired();
        log.debug("DELETE /api/v1/oncall/schedules/{}, tenant={}", id, tenantId);
        service.delete(id, tenantId);
        return ResponseEntity.noContent().build();
    }
}