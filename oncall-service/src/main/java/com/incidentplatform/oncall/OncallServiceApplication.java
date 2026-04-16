package com.incidentplatform.oncall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
        "com.incidentplatform.oncall",
        "com.incidentplatform.shared"
})
public class OncallServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OncallServiceApplication.class, args);
    }
}