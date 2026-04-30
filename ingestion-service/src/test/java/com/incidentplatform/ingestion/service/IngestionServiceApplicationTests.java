package com.incidentplatform.ingestion.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.TestPropertySource;

@ComponentScan(basePackages = {
		"com.incidentplatform.ingestion",
		"com.incidentplatform.shared"
})
@TestPropertySource(properties = {
		"JWT_SECRET=test-secret-key-minimum-32-characters-long-for-tests"
})
class IngestionServiceApplicationTests {
	@Test
	void contextLoads() {
	}
}