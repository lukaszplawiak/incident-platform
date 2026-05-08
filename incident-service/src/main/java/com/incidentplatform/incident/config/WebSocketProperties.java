package com.incidentplatform.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties(prefix = "websocket")
public record WebSocketProperties(List<String> allowedOrigins) {
}