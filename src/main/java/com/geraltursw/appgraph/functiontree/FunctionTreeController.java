package com.geraltursw.appgraph.functiontree;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/appGraph")
public class FunctionTreeController {
    private final FunctionTreeService service;

    public FunctionTreeController(FunctionTreeService service) {
        this.service = service;
    }

    @PostMapping(path = "/api/functionTree/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importTree(
            @RequestPart MultipartFile metadataFile,
            @RequestPart MultipartFile treeFile,
            @RequestParam(defaultValue = "vendor") String source,
            @RequestParam(required = false) String vendorVersion
    ) {
        return service.importTree(metadataFile, treeFile, source, vendorVersion);
    }

    @GetMapping("/api/functionTree/catalogs")
    public Map<String, Object> catalogs(@RequestParam(required = false) String appName) {
        return service.catalogs(appName);
    }

    @GetMapping("/api/functionTree/catalogs/{catalogId}")
    public Map<String, Object> catalog(@PathVariable UUID catalogId) {
        return service.catalog(catalogId);
    }

    @PostMapping("/api/functionMatch/estimate")
    public Map<String, Object> estimate(@Valid @RequestBody MatchRequest request) {
        return service.estimate(request);
    }

    @PostMapping("/api/functionMatch/run")
    public Map<String, Object> run(@Valid @RequestBody MatchRequest request) {
        return service.run(request);
    }

    @GetMapping("/api/functionMatch/runs/{runId}")
    public Map<String, Object> runResult(@PathVariable UUID runId) {
        return service.runResult(runId);
    }

    @GetMapping("/api/functionMatch/runs")
    public Map<String, Object> runs(@RequestParam UUID catalogId) {
        return service.runs(catalogId);
    }

    @GetMapping("/api/functionMatch/runs/{runId}/bindings")
    public Map<String, Object> bindings(
            @PathVariable UUID runId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String reviewStatus
    ) {
        return service.bindings(runId, targetType, reviewStatus);
    }

    @GetMapping("/api/functionMatch/runs/{runId}/coverage")
    public Map<String, Object> coverage(@PathVariable UUID runId) {
        return service.coverage(runId);
    }

    @PostMapping("/api/functionBindings/{bindingId}/review")
    public Map<String, Object> review(
            @PathVariable UUID bindingId,
            @Valid @RequestBody ReviewRequest request
    ) {
        return service.review(bindingId, request);
    }

    @PostMapping("/api/functionBindings/reviewBatch")
    public Map<String, Object> reviewBatch(@Valid @RequestBody BatchReviewRequest request) {
        return service.reviewBatch(request);
    }

    @PostMapping("/api/functionBindings/manual")
    public Map<String, Object> manual(@Valid @RequestBody ManualBindingRequest request) {
        return service.manual(request);
    }

    public record MatchRequest(
            @NotNull UUID catalogId,
            @Min(1) @Max(20) Integer topK,
            @DecimalMin("0") @DecimalMax("1") Double minScore,
            @DecimalMin("0") @DecimalMax("1") Double autoConfirmScore,
            @DecimalMin("0") @DecimalMax("1") Double reviewScore,
            @DecimalMin("0") @DecimalMax("1") Double actionAutoConfirmScore,
            @DecimalMin("0") @DecimalMax("1") Double actionReviewScore,
            @DecimalMin("0") @DecimalMax("1") Double minScoreMargin,
            @DecimalMin("0") @DecimalMax("1") Double aiReviewScore,
            Boolean enableAiReview,
            Boolean enableInheritance,
            @Min(10) @Max(100) Integer aiBatchSize,
            List<UUID> pageIds,
            List<UUID> actionIds
    ) {
        public int resolvedTopK() {
            return topK == null ? 5 : topK;
        }

        public double resolvedMinScore() {
            return minScore == null ? 0.45 : minScore;
        }

        public double resolvedAutoConfirmScore() {
            return autoConfirmScore == null ? 0.85 : autoConfirmScore;
        }

        public double resolvedReviewScore() {
            return reviewScore == null ? 0.55 : reviewScore;
        }

        public double resolvedActionAutoConfirmScore() {
            return actionAutoConfirmScore == null ? 0.90 : actionAutoConfirmScore;
        }

        public double resolvedActionReviewScore() {
            return actionReviewScore == null ? 0.65 : actionReviewScore;
        }

        public double resolvedMinScoreMargin() {
            return minScoreMargin == null ? 0.12 : minScoreMargin;
        }

        public boolean resolvedEnableInheritance() {
            return enableInheritance == null || enableInheritance;
        }
    }

    public record ReviewRequest(
            @NotBlank String targetType,
            @NotBlank String reviewStatus,
            String operatorNote,
            String reviewedBy
    ) {
    }

    public record BatchReviewRequest(
            @NotBlank String targetType,
            @NotBlank String reviewStatus,
            @NotNull List<UUID> bindingIds,
            String operatorNote,
            String reviewedBy
    ) {
    }

    public record ManualBindingRequest(
            @NotNull UUID runId,
            @NotNull UUID functionId,
            @NotBlank String targetType,
            @NotNull UUID targetId,
            String capabilityRole,
            String operatorNote
    ) {
    }
}
