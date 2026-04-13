package com.incidentplatform.postmortem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
		"com.incidentplatform.postmortem",
		"com.incidentplatform.shared"
})
public class PostmortemServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PostmortemServiceApplication.class, args);
	}
}