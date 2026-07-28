package com.geraltursw.appgraph;

import com.geraltursw.appgraph.config.AppGraphProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppGraphProperties.class)
public class AppGraphBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AppGraphBackendApplication.class, args);
	}

}
