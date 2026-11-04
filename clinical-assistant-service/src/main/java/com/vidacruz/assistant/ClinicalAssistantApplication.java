package com.vidacruz.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClinicalAssistantApplication {

	public static void main(String[] args) {
		SpringApplication.run(ClinicalAssistantApplication.class, args);
	}

}
