package com.incidentplatform.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.incidentplatform.ingestion.config.DeduplicationProperties;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@EnableConfigurationProperties(DeduplicationProperties.class)
@ComponentScan(basePackages = {
		"com.incidentplatform.ingestion",
		"com.incidentplatform.shared"
})
public class IngestionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(IngestionServiceApplication.class, args);
	}

}
