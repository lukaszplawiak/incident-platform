package com.incidentplatform.escalation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {
		"com.incidentplatform.escalation",
		"com.incidentplatform.shared"
})
public class EscalationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(EscalationServiceApplication.class, args);
	}
}