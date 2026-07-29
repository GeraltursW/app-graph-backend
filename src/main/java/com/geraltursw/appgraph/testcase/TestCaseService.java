package com.geraltursw.appgraph.testcase;

import com.geraltursw.appgraph.common.JsonSupport;
import com.geraltursw.appgraph.common.NotFoundException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TestCaseService {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonSupport json;

    public TestCaseService(NamedParameterJdbcTemplate jdbc, JsonSupport json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> generate(TestCaseController.GenerateRequest request) {
        var apps = jdbc.queryForList("""
                SELECT app_id, app_name FROM apps WHERE lower(app_name) = lower(:name) LIMIT 1
                """, Map.of("name", request.appName()));
        if (apps.isEmpty()) {
            throw new NotFoundException("App '" + request.appName() + "' not found");
        }
        UUID appId = (UUID) apps.getFirst().get("app_id");
        Graph graph = loadGraph(appId, request.resolvedActionLayers());
        UUID batchId = UUID.randomUUID();
        var config = new LinkedHashMap<String, Object>();
        config.put("modes", request.resolvedModes());
        config.put("actionLayers", request.resolvedActionLayers());
        config.put("scenarioDepth", request.resolvedScenarioDepth());
        config.put("maxScenariosPerPage", request.resolvedMaxScenariosPerPage());
        config.put("collectionMetrics", request.resolvedMetrics());
        config.put("includeAutomationLimited", Boolean.TRUE.equals(request.includeAutomationLimited()));
        jdbc.update("""
                INSERT INTO test_case_batches (
                    batch_id, app_id, run_id, generation_mode, status, config
                ) VALUES (
                    :batchId, :appId, :runId, :mode, 'generating', :config::jsonb
                )
                """, new MapSqlParameterSource()
                .addValue("batchId", batchId).addValue("appId", appId)
                .addValue("runId", request.functionMatchRunId())
                .addValue("mode", String.join("+", request.resolvedModes()))
                .addValue("config", json.write(config)));

        int terminalCases = 0;
        int scenarioCases = 0;
        if (request.resolvedModes().contains("terminalPath")) {
            for (UUID root : graph.roots()) {
                var paths = new ArrayList<List<UUID>>();
                collectRootToLeaf(root, graph.children(), new ArrayList<>(), new HashSet<>(), paths);
                for (List<UUID> path : paths) {
                    persistTerminalCase(batchId, appId, path, graph, request.resolvedMetrics(),
                            resolveFunction(request.functionMatchRunId(), path.getLast(), null));
                    terminalCases++;
                }
            }
        }
        if (request.resolvedModes().contains("processScenario")) {
            for (Page page : graph.pages().values()) {
                List<List<Action>> combinations = actionCombinations(
                        graph.actions().getOrDefault(page.id(), List.of()),
                        request.resolvedScenarioDepth(),
                        request.resolvedMaxScenariosPerPage()
                );
                for (List<Action> actions : combinations) {
                    UUID actionId = actions.isEmpty() ? null : actions.getFirst().id();
                    persistScenarioCase(batchId, appId, page, actions, request.resolvedMetrics(),
                            resolveFunction(request.functionMatchRunId(), page.id(), actionId));
                    scenarioCases++;
                }
            }
        }
        var summary = Map.of(
                "terminalPathCases", terminalCases,
                "processScenarioCases", scenarioCases,
                "totalCases", terminalCases + scenarioCases,
                "pageCount", graph.pages().size(),
                "edgeCount", graph.edges().size()
        );
        jdbc.update("""
                UPDATE test_case_batches
                SET status = 'generated', summary = :summary::jsonb
                WHERE batch_id = :batchId
                """, new MapSqlParameterSource().addValue("summary", json.write(summary))
                .addValue("batchId", batchId));
        return Map.of("status", "success", "batchId", batchId, "summary", summary);
    }

    public Map<String, Object> batch(UUID batchId) {
        var rows = jdbc.queryForList("""
                SELECT b.*, a.app_name FROM test_case_batches b
                JOIN apps a ON a.app_id = b.app_id WHERE b.batch_id = :id
                """, Map.of("id", batchId));
        if (rows.isEmpty()) {
            throw new NotFoundException("Test case batch not found");
        }
        return Map.of("status", "success", "batch", camelize(rows.getFirst()));
    }

    public Map<String, Object> cases(UUID batchId, String caseType) {
        batch(batchId);
        var params = new MapSqlParameterSource().addValue("batchId", batchId);
        String filter = "";
        if (StringUtils.hasText(caseType)) {
            filter = " AND case_type = :caseType";
            params.addValue("caseType", caseType);
        }
        var rows = jdbc.queryForList("""
                SELECT test_case_id, batch_id, case_type, name, start_page_id,
                       terminal_page_id, function_id, steps, collection_policy,
                       expected_result, status, created_at
                FROM test_cases WHERE batch_id = :batchId
                """ + filter + " ORDER BY created_at, name", params);
        return Map.of("status", "success", "cases", rows.stream().map(this::camelize).toList());
    }

    public Map<String, Object> scriptTask(UUID testCaseId) {
        var rows = jdbc.queryForList("""
                SELECT t.*, a.app_name, a.package_name
                FROM test_cases t JOIN apps a ON a.app_id = t.app_id
                WHERE t.test_case_id = :id
                """, Map.of("id", testCaseId));
        if (rows.isEmpty()) {
            throw new NotFoundException("Test case not found");
        }
        var row = camelize(rows.getFirst());
        var task = new LinkedHashMap<String, Object>();
        task.put("taskId", testCaseId);
        task.put("appName", row.get("appName"));
        task.put("packageName", row.get("packageName"));
        task.put("caseType", row.get("caseType"));
        task.put("resetPolicy", Map.of(
                "forceStopBeforeRun", true,
                "returnToHostAppFromMiniProgram", true,
                "dismissPopupAndAds", true,
                "forbidLogout", true
        ));
        task.put("steps", row.get("steps"));
        task.put("collectionPolicy", row.get("collectionPolicy"));
        task.put("expectedResult", row.get("expectedResult"));
        return Map.of("status", "success", "task", task);
    }

    private Graph loadGraph(UUID appId, List<String> layers) {
        var pages = new LinkedHashMap<UUID, Page>();
        jdbc.query("""
                SELECT p.canonical_page_id, p.page_hash_id, p.display_name, p.page_type,
                       coalesce(i.page_url, '') AS page_url
                FROM canonical_pages p
                LEFT JOIN LATERAL (
                    SELECT page_url FROM page_instances pi
                    WHERE pi.canonical_page_id = p.canonical_page_id
                    ORDER BY pi.created_at DESC LIMIT 1
                ) i ON true
                WHERE p.app_id = :appId AND p.page_type <> 'orphan'
                ORDER BY p.created_at
                """, Map.of("appId", appId), rs -> {
            UUID id = rs.getObject("canonical_page_id", UUID.class);
            pages.put(id, new Page(id, rs.getString("page_hash_id"),
                    rs.getString("display_name"), rs.getString("page_url")));
        });
        var edges = jdbc.query("""
                SELECT edge_id, from_canonical_page_id, to_canonical_page_id,
                       action_type, label, widget_description
                FROM page_edges
                WHERE app_id = :appId AND from_canonical_page_id IS NOT NULL
                  AND to_canonical_page_id IS NOT NULL
                ORDER BY created_at
                """, Map.of("appId", appId), (rs, row) -> new Edge(
                rs.getObject("edge_id", UUID.class),
                rs.getObject("from_canonical_page_id", UUID.class),
                rs.getObject("to_canonical_page_id", UUID.class),
                rs.getString("action_type"),
                firstNonBlank(rs.getString("widget_description"), rs.getString("label"))
        ));
        var children = new HashMap<UUID, List<UUID>>();
        var incoming = new HashSet<UUID>();
        for (Edge edge : edges) {
            if (pages.containsKey(edge.from()) && pages.containsKey(edge.to())) {
                children.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
                incoming.add(edge.to());
            }
        }
        List<UUID> roots = pages.keySet().stream().filter(id -> !incoming.contains(id)).toList();
        var actions = new HashMap<UUID, List<Action>>();
        if (!layers.isEmpty()) {
            jdbc.query("""
                    SELECT action_id, canonical_page_id, action_layer, action_type,
                           semantic_name, description, target, parameters, expected_effect
                    FROM page_actions
                    WHERE app_id = :appId AND action_layer IN (:layers)
                    ORDER BY canonical_page_id, created_at
                    """, new MapSqlParameterSource().addValue("appId", appId).addValue("layers", layers), rs -> {
                UUID pageId = rs.getObject("canonical_page_id", UUID.class);
                actions.computeIfAbsent(pageId, ignored -> new ArrayList<>()).add(new Action(
                        rs.getObject("action_id", UUID.class), rs.getString("action_layer"),
                        rs.getString("action_type"), rs.getString("semantic_name"),
                        rs.getString("description"), json.object(rs.getString("target")),
                        json.object(rs.getString("parameters")), json.object(rs.getString("expected_effect"))
                ));
            });
        }
        return new Graph(pages, edges, children, roots, actions);
    }

    private void collectRootToLeaf(
            UUID current,
            Map<UUID, List<UUID>> children,
            List<UUID> path,
            Set<UUID> visited,
            List<List<UUID>> output
    ) {
        if (!visited.add(current)) {
            return;
        }
        var nextPath = new ArrayList<>(path);
        nextPath.add(current);
        List<UUID> next = children.getOrDefault(current, List.of());
        if (next.isEmpty()) {
            output.add(nextPath);
            return;
        }
        for (UUID child : next) {
            collectRootToLeaf(child, children, nextPath, new HashSet<>(visited), output);
        }
    }

    private List<List<Action>> actionCombinations(List<Action> actions, int maxDepth, int limit) {
        var output = new ArrayList<List<Action>>();
        for (Action action : actions) {
            if (output.size() >= limit) break;
            output.add(List.of(action));
        }
        if (maxDepth > 1) {
            for (int left = 0; left < actions.size() && output.size() < limit; left++) {
                for (int right = 0; right < actions.size() && output.size() < limit; right++) {
                    if (left != right) output.add(List.of(actions.get(left), actions.get(right)));
                }
            }
        }
        if (maxDepth > 2) {
            for (int first = 0; first < actions.size() && output.size() < limit; first++) {
                for (int second = 0; second < actions.size() && output.size() < limit; second++) {
                    for (int third = 0; third < actions.size() && output.size() < limit; third++) {
                        if (first != second && second != third && first != third) {
                            output.add(List.of(actions.get(first), actions.get(second), actions.get(third)));
                        }
                    }
                }
            }
        }
        return output;
    }

    private void persistTerminalCase(
            UUID batchId,
            UUID appId,
            List<UUID> path,
            Graph graph,
            List<String> metrics,
            UUID functionId
    ) {
        var steps = new ArrayList<Map<String, Object>>();
        steps.add(Map.of("order", 0, "type", "resetApp", "description", "关闭后台并重新进入应用"));
        for (int index = 0; index < path.size(); index++) {
            Page page = graph.pages().get(path.get(index));
            var step = new LinkedHashMap<String, Object>();
            step.put("order", index + 1);
            step.put("type", index == 0 ? "assertPage" : "navigate");
            step.put("pageId", page.hashId());
            step.put("pageTitle", page.title());
            step.put("pageUrl", page.url());
            if (index > 0) {
                Edge edge = findEdge(graph.edges(), path.get(index - 1), path.get(index));
                if (edge != null) {
                    step.put("actionType", edge.actionType());
                    step.put("control", edge.label());
                    step.put("edgeId", edge.id());
                }
            }
            steps.add(step);
        }
        Page root = graph.pages().get(path.getFirst());
        Page terminal = graph.pages().get(path.getLast());
        persistCase(batchId, appId, "terminalPath",
                "全路径覆盖 · " + root.title() + " → " + terminal.title(),
                root.id(), terminal.id(), functionId, steps,
                Map.of("phase", "terminal", "metrics", metrics, "startAfterFinalPageStable", true),
                Map.of("terminalPageId", terminal.hashId(), "terminalPageTitle", terminal.title(),
                        "allStepsMustPass", true));
    }

    private void persistScenarioCase(
            UUID batchId,
            UUID appId,
            Page page,
            List<Action> actions,
            List<String> metrics,
            UUID functionId
    ) {
        var steps = new ArrayList<Map<String, Object>>();
        steps.add(Map.of("order", 0, "type", "restorePage", "pageId", page.hashId(),
                "pageTitle", page.title(), "strategy", "shortestKnownPath"));
        for (int index = 0; index < actions.size(); index++) {
            Action action = actions.get(index);
            var step = new LinkedHashMap<String, Object>();
            step.put("order", index + 1);
            step.put("type", "performAction");
            step.put("actionId", action.id());
            step.put("actionLayer", action.layer());
            step.put("actionType", action.type());
            step.put("semanticName", action.name());
            step.put("description", action.description());
            step.put("target", action.target());
            step.put("parameters", action.parameters());
            step.put("expectedEffect", action.expectedEffect());
            step.put("collectDuringAction", true);
            steps.add(step);
        }
        String actionNames = actions.stream().map(Action::name).reduce((a, b) -> a + " + " + b).orElse("无动作");
        persistCase(batchId, appId, "processScenario",
                "过程采集 · " + page.title() + " · " + actionNames,
                page.id(), null, functionId, steps,
                Map.of("phase", "duringActions", "metrics", metrics, "sampleContinuously", true),
                Map.of("startPageId", page.hashId(), "actionCount", actions.size(),
                        "captureBeforeAndAfterScreenshot", true));
    }

    private void persistCase(
            UUID batchId,
            UUID appId,
            String type,
            String name,
            UUID startPageId,
            UUID terminalPageId,
            UUID functionId,
            List<Map<String, Object>> steps,
            Map<String, Object> collection,
            Map<String, Object> expected
    ) {
        jdbc.update("""
                INSERT INTO test_cases (
                    test_case_id, batch_id, app_id, case_type, name, start_page_id,
                    terminal_page_id, function_id, steps, collection_policy, expected_result
                ) VALUES (
                    :id, :batchId, :appId, :type, :name, :startPageId,
                    :terminalPageId, :functionId, :steps::jsonb, :collection::jsonb, :expected::jsonb
                )
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("batchId", batchId).addValue("appId", appId)
                .addValue("type", type).addValue("name", name).addValue("startPageId", startPageId)
                .addValue("terminalPageId", terminalPageId).addValue("functionId", functionId)
                .addValue("steps", json.write(steps)).addValue("collection", json.write(collection))
                .addValue("expected", json.write(expected)));
    }

    private UUID resolveFunction(UUID runId, UUID pageId, UUID actionId) {
        if (runId == null) return null;
        if (actionId != null) {
            var functions = jdbc.query("""
                    SELECT function_id FROM function_action_bindings
                    WHERE run_id = :runId AND action_id = :actionId
                      AND review_status IN ('autoConfirmed', 'humanConfirmed', 'inherited', 'confirmed')
                    ORDER BY review_status IN ('humanConfirmed', 'confirmed') DESC,
                             match_score DESC LIMIT 1
                    """, Map.of("runId", runId, "actionId", actionId),
                    (rs, row) -> rs.getObject(1, UUID.class));
            if (!functions.isEmpty()) return functions.getFirst();
        }
        var functions = jdbc.query("""
                SELECT function_id FROM function_page_bindings
                WHERE run_id = :runId AND canonical_page_id = :pageId
                  AND review_status IN ('autoConfirmed', 'humanConfirmed', 'inherited', 'confirmed')
                ORDER BY review_status IN ('humanConfirmed', 'confirmed') DESC,
                         match_score DESC LIMIT 1
                """, Map.of("runId", runId, "pageId", pageId),
                (rs, row) -> rs.getObject(1, UUID.class));
        return functions.isEmpty() ? null : functions.getFirst();
    }

    private Edge findEdge(List<Edge> edges, UUID from, UUID to) {
        return edges.stream().filter(edge -> edge.from().equals(from) && edge.to().equals(to))
                .findFirst().orElse(null);
    }

    private Map<String, Object> camelize(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach((key, value) -> {
            StringBuilder name = new StringBuilder();
            boolean upper = false;
            for (char character : key.toCharArray()) {
                if (character == '_') upper = true;
                else {
                    name.append(upper ? Character.toUpperCase(character) : character);
                    upper = false;
                }
            }
            if (value instanceof org.postgresql.util.PGobject object && "jsonb".equals(object.getType())) {
                String raw = object.getValue();
                value = raw != null && raw.stripLeading().startsWith("[") ? json.array(raw) : json.object(raw);
            }
            result.put(name.toString(), value);
        });
        return result;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value;
        return "";
    }

    private record Page(UUID id, String hashId, String title, String url) {
    }

    private record Edge(UUID id, UUID from, UUID to, String actionType, String label) {
    }

    private record Action(
            UUID id,
            String layer,
            String type,
            String name,
            String description,
            Map<String, Object> target,
            Map<String, Object> parameters,
            Map<String, Object> expectedEffect
    ) {
    }

    private record Graph(
            Map<UUID, Page> pages,
            List<Edge> edges,
            Map<UUID, List<UUID>> children,
            List<UUID> roots,
            Map<UUID, List<Action>> actions
    ) {
    }
}
