package com.incidentplatform.incident;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;

@SpringBootTest
@ComponentScan(basePackages = {
		"com.incidentplatform.incident",
		"com.incidentplatform.shared"
})
class IncidentServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
