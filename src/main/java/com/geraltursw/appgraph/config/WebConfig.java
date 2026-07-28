package com.geraltursw.appgraph.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AppGraphProperties properties;

    public WebConfig(AppGraphProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = properties.corsOrigins() == null || properties.corsOrigins().isEmpty()
                ? new String[]{"http://localhost:5173", "http://localhost:5174"}
                : properties.corsOrigins().toArray(String[]::new);
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}

