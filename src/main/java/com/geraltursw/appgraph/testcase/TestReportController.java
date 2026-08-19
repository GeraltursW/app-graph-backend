package com.geraltursw.appgraph.testcase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/appGraph/api")
public class TestReportController {
    private final TestReportService service;

    public TestReportController(TestReportService service) {
        this.service = service;
    }

    @PostMapping("/testRuns/import")
    public Map<String, Object> importRun(@Valid @RequestBody ImportRunRequest request) {
        return service.importRun(request);
    }

    @GetMapping("/testReports/{runId}")
    public Map<String, Object> report(@PathVariable UUID runId) {
        return service.report(runId);
    }

    @GetMapping("/testReports")
    public Map<String, Object> reports(@RequestParam String appName) {
        return service.reports(appName);
    }

    public record ImportRunRequest(
            @NotNull UUID testCaseId,
            String triggerSource,
            String alertId,
            String alertUrl,
            Map<String, Object> deviceInfo,
            Map<String, Object> environment,
            @NotBlank String status,
            Integer score,
            String verdict,
            String diagnosis,
            Map<String, Object> summary,
            @NotNull Instant startedAt,
            Instant finishedAt,
            List<StepResult> steps,
            List<MetricResult> metrics
    ) {
        public String resolvedTriggerSource() {
            return triggerSource == null || triggerSource.isBlank() ? "manual" : triggerSource;
        }

        public List<StepResult> resolvedSteps() {
            return steps == null ? List.of() : steps;
        }

        public List<MetricResult> resolvedMetrics() {
            return metrics == null ? List.of() : metrics;
        }
    }

    public record StepResult(
            int stepNo,
            @NotBlank String stage,
            @NotBlank String title,
            String action,
            @NotBlank String status,
            long durationMs,
            String beforeImage,
            String afterImage,
            String aiObservation,
            Map<String, Object> rawPayload
    ) {
    }

    public record MetricResult(
            @NotBlank String name,
            Double baseline,
            double actual,
            String unit,
            Double threshold,
            String comparison,
            @NotBlank String status,
            Map<String, Object> sampleSummary
    ) {
    }
}
