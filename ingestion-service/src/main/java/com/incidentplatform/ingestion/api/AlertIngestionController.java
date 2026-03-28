package com.incidentplatform.ingestion.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.incidentplatform.ingestion.service.AlertIngestionService;
import com.incidentplatform.ingestion.service.IngestionSummary;
import com.incidentplatform.shared.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alert Ingestion",
        description = "Endpoints for ingesting alerts from external monitoring systems")
@SecurityRequirement(name = "Bearer Authentication")
public class AlertIngestionController {

    private static final Logger log =
            LoggerFactory.getLogger(AlertIngestionController.class);

    private final AlertIngestionService alertIngestionService;

    public AlertIngestionController(AlertIngestionService alertIngestionService) {
        this.alertIngestionService = alertIngestionService;
    }

    @PostMapping(
            value = "/{source}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ROLE_INGESTOR') or hasRole('ROLE_ADMIN')")
    @Operation(
            summary = "Ingest alerts from external source",
            description = "Accepts alert payload from monitoring system, " +
                    "normalizes it and publishes to Kafka pipeline"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Alerts processed (check summary for details)"),
            @ApiResponse(responseCode = "400",
                    description = "Unknown source or invalid payload format",
                    content = @Content(schema = @Schema(
                            implementation = Map.class))),
            @ApiResponse(responseCode = "401",
                    description = "Missing or invalid JWT token"),
            @ApiResponse(responseCode = "403",
                    description = "Insufficient permissions")
    })
    public ResponseEntity<IngestionSummary> ingestAlerts(
            @Parameter(
                    description = "Alert source identifier",
                    example = "prometheus",
                    schema = @Schema(allowableValues = {
                            "prometheus", "wazuh", "generic"})
            )
            @PathVariable String source,

            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Raw alert payload from external system",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = {
                                    @ExampleObject(
                                            name = "Prometheus payload",
                                            value = """
                                                    {
                                                      "version": "4",
                                                      "status": "firing",
                                                      "alerts": [{
                                                        "status": "firing",
                                                        "labels": {
                                                          "alertname": "HighCpuUsage",
                                                          "severity": "critical",
                                                          "instance": "prod-server-1:9100"
                                                        },
                                                        "annotations": {
                                                          "summary": "High CPU usage"
                                                        },
                                                        "startsAt": "2024-01-15T10:30:00Z"
                                                      }]
                                                    }"""
                                    )
                            }
                    )
            )
            @RequestBody JsonNode rawPayload) {

        final String tenantId = TenantContext.get();

        log.info("Alert ingestion request: source={}, tenant={}", source, tenantId);

        final IngestionSummary summary = alertIngestionService.ingest(
                source, rawPayload, tenantId);

        return ResponseEntity.ok(summary);
    }

    @GetMapping(
            value = "/sources",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ROLE_INGESTOR') or hasRole('ROLE_ADMIN') " +
            "or hasRole('ROLE_RESPONDER')")
    @Operation(
            summary = "List available alert sources",
            description = "Returns list of registered alert normalizers " +
                    "(available source identifiers for webhook configuration)"
    )
    @ApiResponse(responseCode = "200",
            description = "List of available source identifiers")
    public ResponseEntity<List<String>> getAvailableSources() {
        final List<String> sources = alertIngestionService.getAvailableSources();
        log.debug("Available alert sources requested: {}", sources);
        return ResponseEntity.ok(sources);
    }
}