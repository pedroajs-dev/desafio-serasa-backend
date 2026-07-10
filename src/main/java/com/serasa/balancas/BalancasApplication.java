package com.serasa.balancas;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@OpenAPIDefinition(info = @Info(
		title = "Balancas API",
		description = "Cadastre and transport-transaction management for Serasa's weighbridge (balanca) operations"
))
@SpringBootApplication
@ConfigurationPropertiesScan
public class BalancasApplication {

	public static void main(String[] args) {
		SpringApplication.run(BalancasApplication.class, args);
	}

}
