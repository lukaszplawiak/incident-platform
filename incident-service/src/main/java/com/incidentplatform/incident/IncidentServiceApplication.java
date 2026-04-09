package com.incidentplatform.incident;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
		"com.incidentplatform.incident",
		"com.incidentplatform.shared"
})
public class IncidentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(IncidentServiceApplication.class, args);
	}

}
