package com.geraltursw.appgraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties(prefix = "app-graph")
public record AppGraphProperties(
        Path storageRoot,
        List<String> corsOrigins,
        FunctionMatch functionMatch
) {
    public record FunctionMatch(String aiUrl, String aiKey, int timeoutSeconds) {
    }
}

