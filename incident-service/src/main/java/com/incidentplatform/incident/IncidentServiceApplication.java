package com.incidentplatform.incident;

import com.incidentplatform.incident.config.WebSocketProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@EnableConfigurationProperties(WebSocketProperties.class)
@ComponentScan(basePackages = {
		"com.incidentplatform.incident",
		"com.incidentplatform.shared"
})
public class IncidentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(IncidentServiceApplication.class, args);
	}

}
