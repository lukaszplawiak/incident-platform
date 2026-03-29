package com.incidentplatform.incident.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Incident Platform — Incident Service")
                        .description("""
                                Incident lifecycle management service.
                                
                                Consumes alerts from Kafka (alerts.raw, alerts.resolved),
                                creates and manages incidents through their lifecycle:
                                OPEN → ACKNOWLEDGED → RESOLVED → CLOSED
                                
                                Publishes lifecycle events to incidents.lifecycle topic.
                                Provides WebSocket live feed for real-time dashboard updates.
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