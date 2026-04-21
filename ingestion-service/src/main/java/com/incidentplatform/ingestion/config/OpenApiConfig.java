package com.incidentplatform.ingestion.config;

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
                        .title("Incident Platform — Ingestion Service")
                        .description("""
                                Alert ingestion and normalization service.
                                
                                Accepts alerts from multiple sources (Prometheus, Wazuh, 
                                Suricata, GCP Monitoring) and normalizes them to a unified
                                format before publishing to Kafka.
                                
                                All endpoints require JWT Bearer token authentication.
                                """)
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT token from /auth/login endpoint")
                        ));
    }
}