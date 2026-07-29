package com.geraltursw.appgraph.testcase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/appGraph")
public class TestCaseController {
    private final TestCaseService service;

    public TestCaseController(TestCaseService service) {
        this.service = service;
    }

    @PostMapping("/api/testCases/generate")
    public Map<String, Object> generate(@Valid @RequestBody GenerateRequest request) {
        return service.generate(request);
    }

    @GetMapping("/api/testCases/batches/{batchId}")
    public Map<String, Object> batch(@PathVariable UUID batchId) {
        return service.batch(batchId);
    }

    @GetMapping("/api/testCases")
    public Map<String, Object> cases(
            @RequestParam UUID batchId,
            @RequestParam(required = false) String caseType
    ) {
        return service.cases(batchId, caseType);
    }

    @GetMapping("/api/testCases/{testCaseId}/scriptTask")
    public Map<String, Object> scriptTask(@PathVariable UUID testCaseId) {
        return service.scriptTask(testCaseId);
    }

    public record GenerateRequest(
            @NotBlank String appName,
            UUID functionMatchRunId,
            List<String> modes,
            List<String> actionLayers,
            @Min(1) @Max(3) Integer scenarioDepth,
            @Min(1) @Max(100) Integer maxScenariosPerPage,
            List<String> collectionMetrics,
            Boolean includeAutomationLimited
    ) {
        public List<String> resolvedModes() {
            return modes == null || modes.isEmpty()
                    ? List.of("terminalPath", "processScenario") : modes;
        }

        public List<String> resolvedActionLayers() {
            return actionLayers == null || actionLayers.isEmpty()
                    ? List.of("popupAction", "stateAction", "externalAction", "pageNaviAction")
                    : actionLayers;
        }

        public int resolvedScenarioDepth() {
            return scenarioDepth == null ? 2 : scenarioDepth;
        }

        public int resolvedMaxScenariosPerPage() {
            return maxScenariosPerPage == null ? 12 : maxScenariosPerPage;
        }

        public List<String> resolvedMetrics() {
            return collectionMetrics == null || collectionMetrics.isEmpty()
                    ? List.of("power", "cpu", "memory", "fps", "network") : collectionMetrics;
        }
    }
}
