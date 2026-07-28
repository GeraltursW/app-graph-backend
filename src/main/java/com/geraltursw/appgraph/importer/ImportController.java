package com.geraltursw.appgraph.importer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ImportController {
    private final ImportService service;

    public ImportController(ImportService service) {
        this.service = service;
    }

    @PostMapping("/api/imports/scanFolder")
    public Map<String, Object> scanFolder(@Valid @RequestBody FolderRequest request) {
        return service.scanFolder(request.folder());
    }

    public record FolderRequest(@NotBlank String folder) {
    }
}

