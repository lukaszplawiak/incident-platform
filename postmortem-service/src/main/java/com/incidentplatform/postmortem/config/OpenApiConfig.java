package com.incidentplatform.postmortem.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description of postmortem-service, served by springdoc at
 * {@code /v3/api-docs} and {@code /swagger-ui.html}.
 *
 * <p>Defines the {@code "Bearer Authentication"} scheme that
 * {@link com.incidentplatform.postmortem.api.PostmortemController}'s
 * {@code @SecurityRequirement} names, the same way ingestion-service and
 * incident-service do. Before this class and the springdoc dependency, the
 * controller's {@code @Operation} annotations were compiled but never served.
 *
 * <p>Like the other services, the document is public in every profile
 * ({@code SharedSecurityAutoConfiguration.PUBLIC_PATHS}); restricting it to
 * {@code local}/{@code dev} is backlog #0-73.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Incident Platform — Postmortem Service")
                        .description("""
                                AI-generated postmortem reports.

                                Consumes incident lifecycle events from Kafka (incidents.lifecycle),
                                generates a postmortem draft with Gemini when an incident is resolved,
                                and lets responders read, edit and mark postmortems as reviewed.
                                """)
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                        ));
    }
}
