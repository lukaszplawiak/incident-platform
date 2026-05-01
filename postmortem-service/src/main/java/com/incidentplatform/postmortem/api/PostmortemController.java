package com.incidentplatform.postmortem.api;

import com.incidentplatform.postmortem.dto.PostmortemDto;
import com.incidentplatform.postmortem.dto.UpdatePostmortemRequest;
import com.incidentplatform.postmortem.service.PostmortemService;
import com.incidentplatform.shared.security.TenantContext;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/postmortems")
public class PostmortemController {

    private static final Logger log =
            LoggerFactory.getLogger(PostmortemController.class);

    private final PostmortemService postmortemService;

    public PostmortemController(PostmortemService postmortemService) {
        this.postmortemService = postmortemService;
    }

    @GetMapping
    public ResponseEntity<List<PostmortemDto>> getPostmortems() {
        final String tenantId = TenantContext.getRequired();
        log.debug("GET /api/v1/postmortems, tenant={}", tenantId);
        return ResponseEntity.ok(postmortemService.getPostmortems(tenantId));
    }

    @GetMapping("/incident/{incidentId}")
    public ResponseEntity<PostmortemDto> getByIncidentId(
            @PathVariable UUID incidentId) {
        final String tenantId = TenantContext.getRequired();
        log.debug("GET /api/v1/postmortems/incident/{}, tenant={}",
                incidentId, tenantId);
        return ResponseEntity.ok(
                postmortemService.getByIncidentId(incidentId, tenantId));
    }

    @PatchMapping("/incident/{incidentId}")
    public ResponseEntity<PostmortemDto> updateContent(
            @PathVariable UUID incidentId,
            @Valid @RequestBody UpdatePostmortemRequest request) {
        final String tenantId = TenantContext.getRequired();
        log.debug("PATCH /api/v1/postmortems/incident/{}, tenant={}",
                incidentId, tenantId);
        return ResponseEntity.ok(
                postmortemService.updateContent(incidentId, tenantId, request));
    }
}