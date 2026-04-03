package com.incidentplatform.ingestion.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;

@SpringBootTest
@ComponentScan(basePackages = {
		"com.incidentplatform.ingestion",
		"com.incidentplatform.shared"
})
class IngestionServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
