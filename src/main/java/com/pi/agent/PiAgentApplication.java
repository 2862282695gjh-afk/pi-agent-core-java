package com.pi.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot Application entry point for PI Agent Core.
 * 
 * This is optional - the agent can also be used as a library
 * in other Spring Boot applications.
 * 
 * To run:
 * <pre>
 * mvn spring-boot:run
 * </pre>
 * 
 * Or build and run:
 * <pre>
 * mvn package
 * java -jar target/pi-agent-core-0.1.0-SNAPSHOT.jar
 * </pre>
 */
@SpringBootApplication
public class PiAgentApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(PiAgentApplication.class, args);
    }
}
