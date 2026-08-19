package com.geraltursw.appgraph.testcase;

import com.geraltursw.appgraph.common.JsonSupport;
import com.geraltursw.appgraph.common.NotFoundException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TestReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonSupport json;

    public TestReportService(NamedParameterJdbcTemplate jdbc, JsonSupport json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> importRun(TestReportController.ImportRunRequest request) {
        var cases = jdbc.queryForList("""
                SELECT test_case_id, app_id FROM test_cases WHERE test_case_id = :id
                """, Map.of("id", request.testCaseId()));
        if (cases.isEmpty()) throw new NotFoundException("Test case not found");
        UUID appId = (UUID) cases.getFirst().get("app_id");
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO test_execution_runs (
                    run_id, test_case_id, app_id, trigger_source, alert_id, alert_url,
                    device_info, environment, status, score, verdict, diagnosis,
                    summary, started_at, finished_at
                ) VALUES (
                    :runId, :testCaseId, :appId, :triggerSource, :alertId, :alertUrl,
                    :deviceInfo::jsonb, :environment::jsonb, :status, :score, :verdict,
                    :diagnosis, :summary::jsonb, :startedAt, :finishedAt
                )
                """, new MapSqlParameterSource()
                .addValue("runId", runId).addValue("testCaseId", request.testCaseId())
                .addValue("appId", appId).addValue("triggerSource", request.resolvedTriggerSource())
                .addValue("alertId", request.alertId()).addValue("alertUrl", request.alertUrl())
                .addValue("deviceInfo", json.write(request.deviceInfo() == null ? Map.of() : request.deviceInfo()))
                .addValue("environment", json.write(request.environment() == null ? Map.of() : request.environment()))
                .addValue("status", request.status()).addValue("score", request.score())
                .addValue("verdict", request.verdict()).addValue("diagnosis", value(request.diagnosis()))
                .addValue("summary", json.write(request.summary() == null ? Map.of() : request.summary()))
                .addValue("startedAt", Timestamp.from(request.startedAt()))
                .addValue("finishedAt", request.finishedAt() == null ? null : Timestamp.from(request.finishedAt())));

        for (var step : request.resolvedSteps()) {
            jdbc.update("""
                    INSERT INTO test_execution_steps (
                        step_result_id, run_id, step_no, stage, title, action, status,
                        duration_ms, before_image, after_image, ai_observation, raw_payload
                    ) VALUES (
                        :id, :runId, :stepNo, :stage, :title, :action, :status,
                        :durationMs, :beforeImage, :afterImage, :aiObservation, :rawPayload::jsonb
                    )
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                    .addValue("runId", runId).addValue("stepNo", step.stepNo())
                    .addValue("stage", step.stage()).addValue("title", step.title())
                    .addValue("action", value(step.action())).addValue("status", step.status())
                    .addValue("durationMs", step.durationMs()).addValue("beforeImage", step.beforeImage())
                    .addValue("afterImage", step.afterImage()).addValue("aiObservation", value(step.aiObservation()))
                    .addValue("rawPayload", json.write(step.rawPayload() == null ? Map.of() : step.rawPayload())));
        }
        for (var metric : request.resolvedMetrics()) {
            jdbc.update("""
                    INSERT INTO test_metric_results (
                        metric_result_id, run_id, metric_name, baseline_value, actual_value,
                        unit, threshold_value, comparison, status, sample_summary
                    ) VALUES (
                        :id, :runId, :name, :baseline, :actual, :unit, :threshold,
                        :comparison, :status, :sampleSummary::jsonb
                    )
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                    .addValue("runId", runId).addValue("name", metric.name())
                    .addValue("baseline", metric.baseline()).addValue("actual", metric.actual())
                    .addValue("unit", value(metric.unit())).addValue("threshold", metric.threshold())
                    .addValue("comparison", value(metric.comparison(), "lower"))
                    .addValue("status", metric.status())
                    .addValue("sampleSummary", json.write(metric.sampleSummary() == null ? Map.of() : metric.sampleSummary())));
        }
        return Map.of("status", "success", "runId", runId, "reportUrl", "/appGraph/api/testReports/" + runId);
    }

    public Map<String, Object> report(UUID runId) {
        var rows = jdbc.queryForList("""
                SELECT r.*, t.name AS test_case_name, t.case_type, t.steps AS planned_steps,
                       a.app_name, a.package_name
                FROM test_execution_runs r
                JOIN test_cases t ON t.test_case_id = r.test_case_id
                JOIN apps a ON a.app_id = r.app_id
                WHERE r.run_id = :runId
                """, Map.of("runId", runId));
        if (rows.isEmpty()) throw new NotFoundException("Test report not found");
        var report = camelize(rows.getFirst());
        var steps = jdbc.queryForList("""
                SELECT step_no, stage, title, action, status, duration_ms, before_image,
                       after_image, ai_observation, raw_payload
                FROM test_execution_steps WHERE run_id = :runId ORDER BY step_no
                """, Map.of("runId", runId)).stream().map(this::camelize).toList();
        var metrics = jdbc.queryForList("""
                SELECT metric_name, baseline_value, actual_value, unit, threshold_value,
                       comparison, status, sample_summary
                FROM test_metric_results WHERE run_id = :runId ORDER BY metric_name
                """, Map.of("runId", runId)).stream().map(this::camelize).toList();
        report.put("steps", steps);
        report.put("metrics", metrics);
        report.put("failedStepCount", steps.stream().filter(item -> "failed".equals(item.get("status"))).count());
        report.put("failedMetricCount", metrics.stream().filter(item -> "failed".equals(item.get("status"))).count());
        return Map.of("status", "success", "report", report);
    }

    public Map<String, Object> reports(String appName) {
        var rows = jdbc.queryForList("""
                SELECT r.run_id, r.alert_id, r.alert_url, r.status, r.score, r.verdict,
                       r.started_at, r.finished_at, t.name AS test_case_name
                FROM test_execution_runs r
                JOIN apps a ON a.app_id = r.app_id
                JOIN test_cases t ON t.test_case_id = r.test_case_id
                WHERE lower(a.app_name) = lower(:appName)
                ORDER BY r.created_at DESC LIMIT 100
                """, Map.of("appName", appName));
        return Map.of("status", "success", "reports", rows.stream().map(this::camelize).toList());
    }

    private Map<String, Object> camelize(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach((key, original) -> {
            Object value = original;
            if (value instanceof org.postgresql.util.PGobject object && "jsonb".equals(object.getType())) {
                String raw = object.getValue();
                value = raw != null && raw.stripLeading().startsWith("[") ? json.array(raw) : json.object(raw);
            }
            StringBuilder name = new StringBuilder();
            boolean upper = false;
            for (char character : key.toCharArray()) {
                if (character == '_') upper = true;
                else {
                    name.append(upper ? Character.toUpperCase(character) : character);
                    upper = false;
                }
            }
            result.put(name.toString(), value);
        });
        return result;
    }

    private String value(String text) {
        return value(text, "");
    }

    private String value(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }
}
