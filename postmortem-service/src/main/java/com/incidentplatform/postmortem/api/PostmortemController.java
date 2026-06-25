package com.incidentplatform.postmortem.api;

import com.incidentplatform.postmortem.dto.PostmortemDto;
import com.incidentplatform.postmortem.dto.UpdatePostmortemRequest;
import com.incidentplatform.postmortem.service.PostmortemService;
import com.incidentplatform.shared.dto.PagedResponse;
import com.incidentplatform.shared.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/postmortems")
@Tag(name = "Postmortems", description = "Postmortem report management")
@SecurityRequirement(name = "Bearer Authentication")
public class PostmortemController {

    private static final Logger log =
            LoggerFactory.getLogger(PostmortemController.class);

    private final PostmortemService postmortemService;

    public PostmortemController(PostmortemService postmortemService) {
        this.postmortemService = postmortemService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ROLE_RESPONDER') or hasRole('ROLE_ADMIN')")
    @Operation(summary = "List postmortems for the current tenant (paginated)")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Paginated list of postmortems"),
            @ApiResponse(responseCode = "401",
                    description = "Missing or invalid JWT token"),
            @ApiResponse(responseCode = "403",
                    description = "Insufficient permissions — ROLE_RESPONDER or ROLE_ADMIN required")
    })
    public ResponseEntity<PagedResponse<PostmortemDto>> getPostmortems(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        final String tenantId = TenantContext.get();
        log.debug("GET /api/v1/postmortems, tenant={}, page={}",
                tenantId, pageable.getPageNumber());
        final var page = postmortemService.getPostmortems(tenantId, pageable);
        return ResponseEntity.ok(PagedResponse.of(
                page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(),
                page.isFirst(), page.isLast()));
    }

    @GetMapping(
            value = "/incident/{incidentId}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ROLE_RESPONDER') or hasRole('ROLE_ADMIN')")
    @Operation(summary = "Get postmortem for a specific incident")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Postmortem details"),
            @ApiResponse(responseCode = "404",
                    description = "Postmortem not found for this incident"),
            @ApiResponse(responseCode = "401",
                    description = "Missing or invalid JWT token"),
            @ApiResponse(responseCode = "403",
                    description = "Insufficient permissions")
    })
    public ResponseEntity<PostmortemDto> getByIncidentId(
            @PathVariable UUID incidentId) {
        final String tenantId = TenantContext.get();
        log.debug("GET /api/v1/postmortems/incident/{}, tenant={}",
                incidentId, tenantId);
        return ResponseEntity.ok(
                postmortemService.getByIncidentId(incidentId, tenantId));
    }

    @PatchMapping(
            value = "/incident/{incidentId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ROLE_RESPONDER') or hasRole('ROLE_ADMIN')")
    @Operation(summary = "Update postmortem content")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Postmortem content updated"),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed — content is blank"),
            @ApiResponse(responseCode = "404",
                    description = "Postmortem not found for this incident"),
            @ApiResponse(responseCode = "401",
                    description = "Missing or invalid JWT token"),
            @ApiResponse(responseCode = "403",
                    description = "Insufficient permissions")
    })
    public ResponseEntity<PostmortemDto> updateContent(
            @PathVariable UUID incidentId,
            @Valid @RequestBody UpdatePostmortemRequest request) {
        final String tenantId = TenantContext.get();
        log.debug("PATCH /api/v1/postmortems/incident/{}, tenant={}",
                incidentId, tenantId);
        return ResponseEntity.ok(
                postmortemService.updateContent(incidentId, tenantId, request));
    }
}