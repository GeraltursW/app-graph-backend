package com.geraltursw.appgraph.functiontree;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.geraltursw.appgraph.common.ConflictException;
import com.geraltursw.appgraph.common.JsonSupport;
import com.geraltursw.appgraph.common.NotFoundException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class FunctionTreeService {
    private static final String POLICY_VERSION = "function-match-v2";
    private static final List<String> ACTION_LAYERS =
            List.of("popupAction", "stateAction", "externalAction", "pageNaviAction");
    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z0-9][a-z0-9._:/-]+");
    private static final Pattern CHINESE_CHUNK = Pattern.compile("[\\u4e00-\\u9fff]+");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JsonSupport json;

    public FunctionTreeService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, JsonSupport json) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> importTree(
            MultipartFile metadataFile,
            MultipartFile treeFile,
            String source,
            String requestedVendorVersion
    ) {
        try {
            JsonNode metadata = mapper.readTree(metadataFile.getBytes());
            JsonNode tree = mapper.readTree(treeFile.getBytes());
            String appName = firstText(metadata, "appName", "app_name", "name");
            String appVersion = firstText(metadata, "appVersion", "app_version", "version");
            if (appName.isBlank()) {
                throw new IllegalArgumentException("metadata.appName is required");
            }
            UUID appId = findOrCreateApp(appName, metadata, source);
            String vendorVersion = firstNonBlank(
                    requestedVendorVersion,
                    firstText(metadata, "vendorVersion", "vendor_version"),
                    appVersion,
                    "unversioned"
            );
            String payloadHash = sha256(mapper.writeValueAsString(tree));
            var existing = jdbc.queryForList("""
                    SELECT catalog_id, raw_metadata
                    FROM function_catalogs
                    WHERE app_id = :appId AND source = :source AND vendor_version = :version
                    """, new MapSqlParameterSource().addValue("appId", appId)
                    .addValue("source", source).addValue("version", vendorVersion));
            if (!existing.isEmpty()) {
                UUID catalogId = (UUID) existing.getFirst().get("catalog_id");
                String previousHash = value(json.object(existing.getFirst().get("raw_metadata")).get("payloadHash"));
                if (!previousHash.isBlank() && !previousHash.equals(payloadHash)) {
                    throw new ConflictException(
                            "The same vendorVersion already exists with different tree content; publish a new version");
                }
                Integer count = jdbc.queryForObject(
                        "SELECT count(*) FROM function_nodes WHERE catalog_id = :id",
                        Map.of("id", catalogId), Integer.class);
                return Map.of("status", "success", "created", false, "catalogId", catalogId,
                        "appId", appId, "appName", appName, "appVersion", appVersion,
                        "functionCount", count == null ? 0 : count);
            }

            List<ConvertedFunction> converted = convert(tree);
            if (converted.isEmpty()) {
                throw new IllegalArgumentException("No function nodes were found in tree JSON");
            }
            jdbc.update("""
                    UPDATE function_catalogs SET status = 'archived'
                    WHERE app_id = :appId AND source = :source AND status = 'active'
                    """, Map.of("appId", appId, "source", source));
            UUID catalogId = UUID.randomUUID();
            var metadataMap = json.object(mapper.writeValueAsString(metadata));
            metadataMap.put("payloadHash", payloadHash);
            jdbc.update("""
                    INSERT INTO function_catalogs (
                        catalog_id, app_id, app_version, source, vendor_version,
                        schema_version, status, raw_metadata, raw_tree
                    ) VALUES (
                        :id, :appId, :appVersion, :source, :vendorVersion,
                        '1.0', 'active', :metadata::jsonb, :tree::jsonb
                    )
                    """, new MapSqlParameterSource()
                    .addValue("id", catalogId).addValue("appId", appId).addValue("appVersion", appVersion)
                    .addValue("source", source).addValue("vendorVersion", vendorVersion)
                    .addValue("metadata", json.write(metadataMap))
                    .addValue("tree", mapper.writeValueAsString(tree)));
            Map<String, UUID> ids = new HashMap<>();
            converted.sort(Comparator.comparingInt(ConvertedFunction::level)
                    .thenComparingInt(ConvertedFunction::displayOrder));
            for (ConvertedFunction item : converted) {
                UUID id = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO function_nodes (
                            function_id, catalog_id, vendor_function_id, parent_function_id,
                            level, name, description, function_path, display_order,
                            automation_limited, features, expected_capabilities, match_rules, raw_payload
                        ) VALUES (
                            :id, :catalogId, :vendorId, :parentId,
                            :level, :name, :description, :path, :displayOrder,
                            :limited, :features::jsonb, :capabilities::jsonb, :rules::jsonb, :raw::jsonb
                        )
                        """, new MapSqlParameterSource()
                        .addValue("id", id).addValue("catalogId", catalogId)
                        .addValue("vendorId", item.vendorId()).addValue("parentId", ids.get(item.parentVendorId()))
                        .addValue("level", item.level()).addValue("name", item.name())
                        .addValue("description", item.description()).addValue("path", item.path())
                        .addValue("displayOrder", item.displayOrder()).addValue("limited", item.automationLimited())
                        .addValue("features", json.write(item.features()))
                        .addValue("capabilities", json.write(item.expectedCapabilities()))
                        .addValue("rules", json.write(item.matchRules())).addValue("raw", json.write(item.raw())));
                ids.put(item.vendorId(), id);
            }
            long roots = converted.stream().filter(item -> item.parentVendorId() == null).count();
            return Map.of("status", "success", "created", true, "catalogId", catalogId,
                    "appId", appId, "appName", appName, "appVersion", appVersion,
                    "vendorVersion", vendorVersion, "functionCount", converted.size(), "rootCount", roots);
        } catch (ConflictException | IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Function Tree JSON: " + exception.getMessage(), exception);
        }
    }

    public Map<String, Object> catalogs(String appName) {
        var params = new MapSqlParameterSource();
        String filter = "";
        if (StringUtils.hasText(appName)) {
            filter = " WHERE lower(a.app_name) = lower(:appName)";
            params.addValue("appName", appName);
        }
        var rows = jdbc.queryForList("""
                SELECT c.catalog_id, c.app_id, a.app_name, c.app_version, c.source,
                       c.vendor_version, c.status, c.imported_at,
                       count(n.function_id) AS function_count
                FROM function_catalogs c
                JOIN apps a ON a.app_id = c.app_id
                LEFT JOIN function_nodes n ON n.catalog_id = c.catalog_id
                """ + filter + """
                GROUP BY c.catalog_id, a.app_name
                ORDER BY c.imported_at DESC
                """, params);
        return Map.of("status", "success", "catalogs", camelizeRows(rows));
    }

    public Map<String, Object> catalog(UUID catalogId) {
        var catalogs = jdbc.queryForList("""
                SELECT c.*, a.app_name
                FROM function_catalogs c JOIN apps a ON a.app_id = c.app_id
                WHERE c.catalog_id = :id
                """, Map.of("id", catalogId));
        if (catalogs.isEmpty()) {
            throw new NotFoundException("Function catalog not found");
        }
        var nodes = jdbc.queryForList("""
                SELECT function_id, vendor_function_id, parent_function_id, level, name,
                       description, function_path, display_order, automation_limited,
                       features, expected_capabilities, match_rules
                FROM function_nodes WHERE catalog_id = :id ORDER BY display_order
                """, Map.of("id", catalogId));
        return Map.of("status", "success", "catalog", camelize(catalogs.getFirst()),
                "roots", buildFunctionTree(nodes));
    }

    public Map<String, Object> estimate(FunctionTreeController.MatchRequest request) {
        Context context = loadContext(request.catalogId(), request.pageIds(), request.actionIds());
        int averageTokens = Math.max(1, context.functions().stream()
                .mapToInt(item -> item.tokens().size()).sum() / Math.max(1, context.functions().size()));
        long localComparisons = (long) (context.pages().size() + context.actions().size())
                * Math.min(context.functions().size(), Math.max(8, averageTokens * 3));
        long fullCartesian = (long) (context.pages().size() + context.actions().size())
                * context.functions().size();
        int ambiguousUpperBound = (int) Math.ceil((context.pages().size() + context.actions().size()) * 0.2);
        int batchSize = request.aiBatchSize() == null ? 40 : request.aiBatchSize();
        int aiCalls = Boolean.TRUE.equals(request.enableAiReview())
                ? (int) Math.ceil(ambiguousUpperBound / (double) batchSize) : 0;
        return Map.of(
                "status", "success",
                "functionCount", context.functions().size(),
                "pageCount", context.pages().size(),
                "actionCount", context.actions().size(),
                "fullCartesianComparisons", fullCartesian,
                "estimatedLocalComparisons", localComparisons,
                "estimatedAiCandidates", ambiguousUpperBound,
                "estimatedAiCalls", aiCalls,
                "strategy", "Inverted-token retrieval, local scoring, optional batched AI review"
        );
    }

    @Transactional
    public Map<String, Object> run(FunctionTreeController.MatchRequest request) {
        Context context = loadContext(request.catalogId(), request.pageIds(), request.actionIds());
        UUID runId = UUID.randomUUID();
        String graphVersion = Instant.now().toString();
        jdbc.update("""
                INSERT INTO function_match_runs (
                    run_id, catalog_id, app_id, graph_version, status, config, summary
                ) VALUES (
                    :runId, :catalogId, :appId, :version, 'running', :config::jsonb, '{}'::jsonb
                )
                """, new MapSqlParameterSource().addValue("runId", runId)
                .addValue("catalogId", request.catalogId()).addValue("appId", context.appId())
                .addValue("version", graphVersion).addValue("config", json.write(request)));
        try {
            var tokenIndex = buildIndex(context.functions());
            var functionsById = new HashMap<UUID, Document>();
            context.functions().forEach(function -> functionsById.put(function.id(), function));
            Map<String, UUID> inheritedPages = request.resolvedEnableInheritance()
                    ? loadInheritedBindings(context.appId(), runId, "page") : Map.of();
            Map<String, UUID> inheritedActions = request.resolvedEnableInheritance()
                    ? loadInheritedBindings(context.appId(), runId, "action") : Map.of();
            int pageBindings = 0;
            int actionBindings = 0;
            for (Document page : context.pages()) {
                var scored = scoreCandidates(page, context.functions(), tokenIndex,
                        request.resolvedTopK(), request.resolvedMinScore(), false);
                for (Decision decision : decideCandidates(
                        page.id(), "page", scored, functionsById, inheritedPages, request)) {
                    jdbc.update("""
                            INSERT INTO function_page_bindings (
                                binding_id, run_id, function_id, canonical_page_id,
                                binding_source, match_score, match_evidence, review_status, is_primary,
                                second_best_score, score_margin, policy_version, decision_source,
                                inherited_from
                            ) VALUES (
                                :id, :runId, :functionId, :targetId,
                                :source, :score, :evidence::jsonb, :status, :primary,
                                :secondBest, :margin, :policyVersion, :source, :inheritedFrom
                            )
                            """, bindingParams(runId, page.id(), decision));
                    pageBindings++;
                }
            }
            Map<UUID, Document> pageById = new HashMap<>();
            context.pages().forEach(page -> pageById.put(page.id(), page));
            for (Document action : context.actions()) {
                Document sourcePage = pageById.get(action.parentId());
                var scored = scoreActionCandidates(action, sourcePage, context.functions(), tokenIndex,
                        request.resolvedTopK(), request.resolvedMinScore());
                for (Decision decision : decideCandidates(
                        action.id(), "action", scored, functionsById, inheritedActions, request)) {
                    var params = bindingParams(runId, action.id(), decision);
                    params.addValue("role", capabilityRole(action.layer()));
                    jdbc.update("""
                            INSERT INTO function_action_bindings (
                                binding_id, run_id, function_id, action_id, capability_role,
                                binding_source, match_score, match_evidence, review_status, is_primary,
                                second_best_score, score_margin, policy_version, decision_source,
                                inherited_from
                            ) VALUES (
                                :id, :runId, :functionId, :targetId, :role,
                                :source, :score, :evidence::jsonb, :status, :primary,
                                :secondBest, :margin, :policyVersion, :source, :inheritedFrom
                            )
                            """, params);
                    actionBindings++;
                }
            }
            var summary = new LinkedHashMap<String, Object>();
            summary.put("functionCount", context.functions().size());
            summary.put("pageCount", context.pages().size());
            summary.put("actionCount", context.actions().size());
            summary.put("pageBindingCount", pageBindings);
            summary.put("actionBindingCount", actionBindings);
            summary.put("decisionCounts", decisionCounts(runId));
            summary.put("policyVersion", POLICY_VERSION);
            summary.put("aiReviewEnabled", Boolean.TRUE.equals(request.enableAiReview()));
            summary.put("aiReviewStatus", Boolean.TRUE.equals(request.enableAiReview())
                    ? "providerNotConfiguredOrDeferred" : "disabled");
            jdbc.update("""
                    UPDATE function_match_runs
                    SET status = 'completed', summary = :summary::jsonb, finished_at = now()
                    WHERE run_id = :runId
                    """, new MapSqlParameterSource().addValue("summary", json.write(summary)).addValue("runId", runId));
            return Map.of("status", "success", "runId", runId, "summary", summary);
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE function_match_runs
                    SET status = 'failed', error_message = :message, finished_at = now()
                    WHERE run_id = :runId
                    """, Map.of("message", exception.getMessage(), "runId", runId));
            throw exception;
        }
    }

    public Map<String, Object> runResult(UUID runId) {
        var rows = jdbc.queryForList("SELECT * FROM function_match_runs WHERE run_id = :id",
                Map.of("id", runId));
        if (rows.isEmpty()) {
            throw new NotFoundException("Match run not found");
        }
        return Map.of("status", "success", "run", camelize(rows.getFirst()));
    }

    public Map<String, Object> runs(UUID catalogId) {
        var rows = jdbc.queryForList("""
                SELECT run_id, catalog_id, app_id, graph_version, status, config, summary,
                       error_message, started_at, finished_at
                FROM function_match_runs
                WHERE catalog_id = :catalogId
                ORDER BY started_at DESC
                """, Map.of("catalogId", catalogId));
        return Map.of("status", "success", "runs", camelizeRows(rows));
    }

    public Map<String, Object> bindings(UUID runId, String targetType, String reviewStatus) {
        runResult(runId);
        var pageBindings = new ArrayList<Map<String, Object>>();
        var actionBindings = new ArrayList<Map<String, Object>>();
        if (!"action".equals(targetType)) {
            var params = new MapSqlParameterSource().addValue("runId", runId);
            String filter = "";
            if (StringUtils.hasText(reviewStatus)) {
                filter = " AND b.review_status = :reviewStatus";
                params.addValue("reviewStatus", reviewStatus);
            }
            pageBindings.addAll(camelizeRows(jdbc.queryForList("""
                    SELECT b.*, f.name AS function_name, f.function_path, p.display_name AS page_title,
                           p.page_hash_id
                    FROM function_page_bindings b
                    JOIN function_nodes f ON f.function_id = b.function_id
                    JOIN canonical_pages p ON p.canonical_page_id = b.canonical_page_id
                    WHERE b.run_id = :runId
                    """ + filter + " ORDER BY b.match_score DESC", params)));
        }
        if (!"page".equals(targetType)) {
            var params = new MapSqlParameterSource().addValue("runId", runId);
            String filter = "";
            if (StringUtils.hasText(reviewStatus)) {
                filter = " AND b.review_status = :reviewStatus";
                params.addValue("reviewStatus", reviewStatus);
            }
            actionBindings.addAll(camelizeRows(jdbc.queryForList("""
                    SELECT b.*, f.name AS function_name, f.function_path,
                           a.semantic_name AS action_name, a.action_layer, a.action_type,
                           p.display_name AS page_title, p.page_hash_id
                    FROM function_action_bindings b
                    JOIN function_nodes f ON f.function_id = b.function_id
                    JOIN page_actions a ON a.action_id = b.action_id
                    JOIN canonical_pages p ON p.canonical_page_id = a.canonical_page_id
                    WHERE b.run_id = :runId
                    """ + filter + " ORDER BY b.match_score DESC", params)));
        }
        return Map.of("status", "success", "pageBindings", pageBindings, "actionBindings", actionBindings);
    }

    public Map<String, Object> coverage(UUID runId) {
        runResult(runId);
        var functions = camelizeRows(jdbc.queryForList("""
                SELECT f.function_id, f.parent_function_id, f.name, f.function_path,
                       f.automation_limited,
                       coalesce(p.total, 0) AS page_total,
                       coalesce(p.auto_confirmed, 0) AS page_auto_confirmed,
                       coalesce(p.human_confirmed, 0) AS page_human_confirmed,
                       coalesce(p.inherited, 0) AS page_inherited,
                       coalesce(p.pending, 0) AS page_pending,
                       coalesce(p.conflicted, 0) AS page_conflicted,
                       coalesce(a.total, 0) AS action_total,
                       coalesce(a.auto_confirmed, 0) AS action_auto_confirmed,
                       coalesce(a.human_confirmed, 0) AS action_human_confirmed,
                       coalesce(a.inherited, 0) AS action_inherited,
                       coalesce(a.pending, 0) AS action_pending,
                       coalesce(a.conflicted, 0) AS action_conflicted
                FROM function_nodes f
                LEFT JOIN (
                    SELECT function_id, count(*) AS total,
                           count(*) FILTER (WHERE review_status = 'autoConfirmed') AS auto_confirmed,
                           count(*) FILTER (WHERE review_status IN ('humanConfirmed', 'confirmed')) AS human_confirmed,
                           count(*) FILTER (WHERE review_status = 'inherited') AS inherited,
                           count(*) FILTER (WHERE review_status IN ('pendingReview', 'suggested')) AS pending,
                           count(*) FILTER (WHERE review_status = 'conflicted') AS conflicted
                    FROM function_page_bindings WHERE run_id = :runId GROUP BY function_id
                ) p ON p.function_id = f.function_id
                LEFT JOIN (
                    SELECT function_id, count(*) AS total,
                           count(*) FILTER (WHERE review_status = 'autoConfirmed') AS auto_confirmed,
                           count(*) FILTER (WHERE review_status IN ('humanConfirmed', 'confirmed')) AS human_confirmed,
                           count(*) FILTER (WHERE review_status = 'inherited') AS inherited,
                           count(*) FILTER (WHERE review_status IN ('pendingReview', 'suggested')) AS pending,
                           count(*) FILTER (WHERE review_status = 'conflicted') AS conflicted
                    FROM function_action_bindings WHERE run_id = :runId GROUP BY function_id
                ) a ON a.function_id = f.function_id
                WHERE f.catalog_id = (
                    SELECT catalog_id FROM function_match_runs WHERE run_id = :runId
                )
                ORDER BY f.display_order
                """, Map.of("runId", runId)));
        return Map.of(
                "status", "success",
                "runId", runId,
                "summary", decisionCounts(runId),
                "functions", functions
        );
    }

    @Transactional
    public Map<String, Object> review(UUID bindingId, FunctionTreeController.ReviewRequest request) {
        if (!Set.of("page", "action").contains(request.targetType())) {
            throw new IllegalArgumentException("targetType must be page or action");
        }
        String status = normalizeHumanReviewStatus(request.reviewStatus());
        String table = "page".equals(request.targetType())
                ? "function_page_bindings" : "function_action_bindings";
        int count = jdbc.update("UPDATE " + table + """
                SET review_status = :status, decision_source = 'human',
                    operator_note = :note, reviewed_by = :reviewedBy, reviewed_at = now()
                WHERE binding_id = :id
                """, new MapSqlParameterSource()
                .addValue("status", status).addValue("note", value(request.operatorNote()))
                .addValue("reviewedBy", firstNonBlank(request.reviewedBy(), "demo-user"))
                .addValue("id", bindingId));
        if (count == 0) {
            throw new NotFoundException("Binding not found");
        }
        return Map.of("status", "success", "bindingId", bindingId, "reviewStatus", status);
    }

    @Transactional
    public Map<String, Object> reviewBatch(FunctionTreeController.BatchReviewRequest request) {
        if (!Set.of("page", "action").contains(request.targetType())) {
            throw new IllegalArgumentException("targetType must be page or action");
        }
        if (request.bindingIds().isEmpty()) {
            throw new IllegalArgumentException("bindingIds must not be empty");
        }
        String status = normalizeHumanReviewStatus(request.reviewStatus());
        String table = "page".equals(request.targetType())
                ? "function_page_bindings" : "function_action_bindings";
        int updated = jdbc.update("UPDATE " + table + """
                SET review_status = :status, decision_source = 'human',
                    operator_note = :note, reviewed_by = :reviewedBy, reviewed_at = now()
                WHERE binding_id IN (:bindingIds)
                """, new MapSqlParameterSource()
                .addValue("status", status).addValue("note", value(request.operatorNote()))
                .addValue("reviewedBy", firstNonBlank(request.reviewedBy(), "demo-user"))
                .addValue("bindingIds", request.bindingIds()));
        return Map.of("status", "success", "updated", updated, "reviewStatus", status);
    }

    private String normalizeHumanReviewStatus(String status) {
        if ("confirmed".equals(status)) return "humanConfirmed";
        if (!Set.of("humanConfirmed", "rejected").contains(status)) {
            throw new IllegalArgumentException("reviewStatus is invalid");
        }
        return status;
    }

    @Transactional
    public Map<String, Object> manual(FunctionTreeController.ManualBindingRequest request) {
        if (!Set.of("page", "action").contains(request.targetType())) {
            throw new IllegalArgumentException("targetType must be page or action");
        }
        UUID id = UUID.randomUUID();
        if ("page".equals(request.targetType())) {
            jdbc.update("""
                    INSERT INTO function_page_bindings (
                        binding_id, run_id, function_id, canonical_page_id, binding_source,
                        match_score, match_evidence, review_status, is_primary, operator_note,
                        policy_version, decision_source, reviewed_by, reviewed_at
                    ) VALUES (
                        :id, :runId, :functionId, :targetId, 'manual',
                        1, '{"reason":"manual binding"}'::jsonb, 'humanConfirmed', true, :note,
                        :policyVersion, 'manual', 'demo-user', now()
                    )
                    """, manualParams(id, request).addValue("policyVersion", POLICY_VERSION));
        } else {
            var params = manualParams(id, request)
                    .addValue("role", firstNonBlank(request.capabilityRole(), "behavior"));
            jdbc.update("""
                    INSERT INTO function_action_bindings (
                        binding_id, run_id, function_id, action_id, capability_role, binding_source,
                        match_score, match_evidence, review_status, is_primary, operator_note,
                        policy_version, decision_source, reviewed_by, reviewed_at
                    ) VALUES (
                        :id, :runId, :functionId, :targetId, :role, 'manual',
                        1, '{"reason":"manual binding"}'::jsonb, 'humanConfirmed', true, :note,
                        :policyVersion, 'manual', 'demo-user', now()
                    )
                    """, params.addValue("policyVersion", POLICY_VERSION));
        }
        return Map.of("status", "success", "bindingId", id, "reviewStatus", "humanConfirmed");
    }

    private Context loadContext(UUID catalogId, List<UUID> pageFilter, List<UUID> actionFilter) {
        var catalog = jdbc.queryForList(
                "SELECT app_id FROM function_catalogs WHERE catalog_id = :id", Map.of("id", catalogId));
        if (catalog.isEmpty()) {
            throw new NotFoundException("Function catalog not found");
        }
        UUID appId = (UUID) catalog.getFirst().get("app_id");
        List<Document> functions = jdbc.query("""
                SELECT function_id, vendor_function_id, name, description, function_path,
                       match_rules, parent_function_id
                FROM function_nodes WHERE catalog_id = :id ORDER BY display_order
                """, Map.of("id", catalogId), (rs, row) -> {
            String text = String.join(" ", rs.getString("name"), rs.getString("description"),
                    rs.getString("function_path"), rs.getString("match_rules"));
            var metadata = json.object(rs.getString("match_rules"));
            metadata.put("vendorFunctionId", rs.getString("vendor_function_id"));
            return new Document(rs.getObject("function_id", UUID.class), null, rs.getString("name"),
                    text, tokens(text), "", metadata);
        });
        var pageParams = new MapSqlParameterSource().addValue("appId", appId);
        String pageWhere = "";
        if (pageFilter != null && !pageFilter.isEmpty()) {
            pageWhere = " AND p.canonical_page_id IN (:pageIds)";
            pageParams.addValue("pageIds", pageFilter);
        }
        List<Document> pages = jdbc.query("""
                SELECT p.canonical_page_id, p.display_name, p.page_type,
                       coalesce(i.ai_summary, '') AS ai_summary,
                       coalesce(i.page_url, '') AS page_url,
                       coalesce(i.ocr_text, '') AS ocr_text
                FROM canonical_pages p
                LEFT JOIN LATERAL (
                    SELECT * FROM page_instances pi WHERE pi.canonical_page_id = p.canonical_page_id
                    ORDER BY pi.created_at DESC LIMIT 1
                ) i ON true
                WHERE p.app_id = :appId
                """ + pageWhere, pageParams, (rs, row) -> {
            String text = String.join(" ", rs.getString("display_name"), rs.getString("page_type"),
                    rs.getString("ai_summary"), rs.getString("page_url"), rs.getString("ocr_text"));
            return new Document(rs.getObject("canonical_page_id", UUID.class), null,
                    rs.getString("display_name"), text, tokens(text), "", Map.of());
        });
        var actionParams = new MapSqlParameterSource().addValue("appId", appId);
        String actionWhere = "";
        if (actionFilter != null && !actionFilter.isEmpty()) {
            actionWhere = " AND a.action_id IN (:actionIds)";
            actionParams.addValue("actionIds", actionFilter);
        }
        List<Document> actions = jdbc.query("""
                SELECT a.action_id, a.canonical_page_id, a.semantic_name, a.description,
                       a.action_type, a.action_layer, a.target, a.parameters
                FROM page_actions a WHERE a.app_id = :appId
                """ + actionWhere, actionParams, (rs, row) -> {
            String text = String.join(" ", rs.getString("semantic_name"), rs.getString("description"),
                    rs.getString("action_type"), rs.getString("action_layer"),
                    rs.getString("target"), rs.getString("parameters"));
            return new Document(rs.getObject("action_id", UUID.class),
                    rs.getObject("canonical_page_id", UUID.class), rs.getString("semantic_name"),
                    text, tokens(text), rs.getString("action_layer"), Map.of());
        });
        return new Context(appId, functions, pages, actions);
    }

    private Map<String, Set<UUID>> buildIndex(List<Document> functions) {
        Map<String, Set<UUID>> index = new HashMap<>();
        for (Document function : functions) {
            for (String token : function.tokens()) {
                index.computeIfAbsent(token, ignored -> new LinkedHashSet<>()).add(function.id());
            }
        }
        return index;
    }

    private List<Scored> scoreCandidates(
            Document target,
            List<Document> functions,
            Map<String, Set<UUID>> index,
            int topK,
            double minScore,
            boolean action
    ) {
        Set<UUID> candidates = retrieve(target.tokens(), functions, index);
        return functions.stream()
                .filter(function -> candidates.contains(function.id()))
                .map(function -> scorePage(target, function))
                .filter(scored -> scored.score() >= minScore)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .toList();
    }

    private List<Scored> scoreActionCandidates(
            Document action,
            Document page,
            List<Document> functions,
            Map<String, Set<UUID>> index,
            int topK,
            double minScore
    ) {
        Set<String> targetTokens = new HashSet<>(action.tokens());
        if (page != null) {
            targetTokens.addAll(page.tokens());
        }
        Set<UUID> candidates = retrieve(targetTokens, functions, index);
        return functions.stream()
                .filter(function -> candidates.contains(function.id()))
                .map(function -> scoreAction(action, page, function))
                .filter(scored -> Boolean.TRUE.equals(scored.evidence().get("eligible")))
                .filter(scored -> scored.score() >= minScore)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .toList();
    }

    private Set<UUID> retrieve(Set<String> tokens, List<Document> functions, Map<String, Set<UUID>> index) {
        var result = new LinkedHashSet<UUID>();
        for (String token : tokens) {
            result.addAll(index.getOrDefault(token, Set.of()));
        }
        if (result.isEmpty()) {
            functions.stream().limit(16).map(Document::id).forEach(result::add);
        }
        return result;
    }

    private Scored scorePage(Document page, Document function) {
        double coverage = coverage(page.tokens(), function.tokens());
        double sequence = trigramSimilarity(page.text(), function.text());
        boolean nameHit = page.text().toLowerCase(Locale.ROOT)
                .contains(function.name().toLowerCase(Locale.ROOT));
        double rawScore = coverage * 0.55 + sequence * 0.15 + (nameHit ? 0.30 : 0);
        double score = Math.min(1, rawScore / 0.55);
        return new Scored(function.id(), round(score), Map.of(
                "matchedTerms", intersection(page.tokens(), function.tokens()),
                "pageSimilarity", round(coverage), "sequenceSimilarity", round(sequence),
                "rawScore", round(rawScore), "calibration", "raw/0.55",
                "nameHit", nameHit, "reason", "Local lexical retrieval with Function Tree context"
        ));
    }

    private Scored scoreAction(Document action, Document page, Document function) {
        double actionSimilarity = coverage(action.tokens(), function.tokens());
        double pageSimilarity = page == null ? 0 : coverage(page.tokens(), function.tokens());
        @SuppressWarnings("unchecked")
        List<Object> allowed = (List<Object>) function.metadata().getOrDefault("allowedLayers", List.of());
        boolean compatible = allowed.isEmpty() || allowed.contains(action.layer());
        boolean nameHit = action.text().toLowerCase(Locale.ROOT)
                .contains(function.name().toLowerCase(Locale.ROOT));
        boolean semanticHit = actionSimilarity > 0 || nameHit;
        boolean contextualHit = pageSimilarity >= 0.15;
        boolean eligible = compatible && (semanticHit || contextualHit);
        double rawScore = eligible
                ? actionSimilarity * 0.50 + pageSimilarity * 0.20
                    + (compatible ? 0.10 : 0) + (nameHit ? 0.20 : 0)
                : 0;
        double score = Math.min(1, rawScore / 0.80);
        return new Scored(function.id(), round(score), Map.ofEntries(
                Map.entry("matchedTerms", intersection(action.tokens(), function.tokens())),
                Map.entry("actionSimilarity", round(actionSimilarity)),
                Map.entry("pageSimilarity", round(pageSimilarity)),
                Map.entry("layerCompatible", compatible),
                Map.entry("semanticHit", semanticHit),
                Map.entry("contextualHit", contextualHit),
                Map.entry("eligible", eligible),
                Map.entry("nameHit", nameHit),
                Map.entry("rawScore", round(rawScore)),
                Map.entry("calibration", "raw/0.80"),
                Map.entry("actionLayer", action.layer()),
                Map.entry("reason", "Action semantics, source-page context and layer compatibility")
        ));
    }

    private List<Decision> decideCandidates(
            UUID targetId,
            String targetType,
            List<Scored> candidates,
            Map<UUID, Document> functionsById,
            Map<String, UUID> inherited,
            FunctionTreeController.MatchRequest request
    ) {
        if (candidates.isEmpty()) return List.of();
        Scored best = candidates.getFirst();
        double secondBest = candidates.size() > 1 ? candidates.get(1).score() : 0;
        double margin = round(best.score() - secondBest);
        double reviewScore = "action".equals(targetType)
                ? request.resolvedActionReviewScore() : request.resolvedReviewScore();
        double autoConfirmScore = "action".equals(targetType)
                ? request.resolvedActionAutoConfirmScore() : request.resolvedAutoConfirmScore();
        boolean conflict = candidates.size() > 1
                && secondBest >= reviewScore
                && margin < request.resolvedMinScoreMargin();

        if (best.score() < reviewScore) return List.of();
        if (conflict) {
            var decisions = new ArrayList<Decision>();
            for (int index = 0; index < candidates.size(); index++) {
                Scored candidate = candidates.get(index);
                if (candidate.score() < reviewScore
                        || best.score() - candidate.score() >= request.resolvedMinScoreMargin()) {
                    break;
                }
                decisions.add(new Decision(candidate, "conflicted", "policy",
                        secondBest, margin, index == 0, null));
            }
            return decisions;
        }

        Document function = functionsById.get(best.functionId());
        UUID inheritedFrom = inherited.get(inheritanceKey(targetId, function));
        if (inheritedFrom != null) {
            return List.of(new Decision(best, "inherited", "inherited",
                    secondBest, margin, true, inheritedFrom));
        }

        boolean evidenceReady = autoConfirmEvidence(targetType, best.evidence());
        String status = best.score() >= autoConfirmScore
                && margin >= request.resolvedMinScoreMargin()
                && evidenceReady
                ? "autoConfirmed" : "pendingReview";
        return List.of(new Decision(best, status, "policy", secondBest, margin, true, null));
    }

    private boolean autoConfirmEvidence(String targetType, Map<String, Object> evidence) {
        if ("action".equals(targetType)) {
            return Boolean.TRUE.equals(evidence.get("layerCompatible"))
                    && Boolean.TRUE.equals(evidence.get("semanticHit"))
                    && (Boolean.TRUE.equals(evidence.get("nameHit"))
                        || number(evidence.get("actionSimilarity")) >= 0.25);
        }
        return Boolean.TRUE.equals(evidence.get("nameHit"))
                || number(evidence.get("pageSimilarity")) >= 0.35;
    }

    private Map<String, UUID> loadInheritedBindings(UUID appId, UUID currentRunId, String targetType) {
        String table = "action".equals(targetType)
                ? "function_action_bindings" : "function_page_bindings";
        String targetColumn = "action".equals(targetType) ? "action_id" : "canonical_page_id";
        String sql = """
                WITH previous_run AS (
                    SELECT run_id
                    FROM function_match_runs
                    WHERE app_id = :appId AND run_id <> :currentRunId AND status = 'completed'
                    ORDER BY started_at DESC
                    LIMIT 1
                )
                SELECT b.binding_id, b.%s AS target_id, f.vendor_function_id
                FROM %s b
                JOIN previous_run r ON r.run_id = b.run_id
                JOIN function_nodes f ON f.function_id = b.function_id
                WHERE b.review_status IN ('autoConfirmed', 'humanConfirmed', 'inherited', 'confirmed')
                ORDER BY b.match_score DESC
                """.formatted(targetColumn, table);
        var rows = jdbc.queryForList(sql, Map.of("appId", appId, "currentRunId", currentRunId));
        var inherited = new LinkedHashMap<String, UUID>();
        rows.forEach(row -> inherited.putIfAbsent(
                row.get("target_id") + "|" + row.get("vendor_function_id"),
                (UUID) row.get("binding_id")));
        return inherited;
    }

    private String inheritanceKey(UUID targetId, Document function) {
        return targetId + "|" + value(function.metadata().get("vendorFunctionId"));
    }

    private Map<String, Long> decisionCounts(UUID runId) {
        var rows = jdbc.queryForList("""
                SELECT review_status, count(*) AS binding_count
                FROM (
                    SELECT review_status FROM function_page_bindings WHERE run_id = :runId
                    UNION ALL
                    SELECT review_status FROM function_action_bindings WHERE run_id = :runId
                ) bindings
                GROUP BY review_status
                """, Map.of("runId", runId));
        var counts = new LinkedHashMap<String, Long>();
        rows.forEach(row -> counts.put(
                value(row.get("review_status")),
                ((Number) row.get("binding_count")).longValue()));
        return counts;
    }

    private MapSqlParameterSource bindingParams(UUID runId, UUID targetId, Decision decision) {
        return new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("runId", runId)
                .addValue("functionId", decision.scored().functionId()).addValue("targetId", targetId)
                .addValue("score", decision.scored().score())
                .addValue("evidence", json.write(decision.scored().evidence()))
                .addValue("status", decision.status()).addValue("primary", decision.primary())
                .addValue("secondBest", decision.secondBest()).addValue("margin", decision.margin())
                .addValue("policyVersion", POLICY_VERSION).addValue("source", decision.source())
                .addValue("inheritedFrom", decision.inheritedFrom());
    }

    private MapSqlParameterSource manualParams(UUID id, FunctionTreeController.ManualBindingRequest request) {
        return new MapSqlParameterSource().addValue("id", id).addValue("runId", request.runId())
                .addValue("functionId", request.functionId()).addValue("targetId", request.targetId())
                .addValue("note", value(request.operatorNote()));
    }

    private List<ConvertedFunction> convert(JsonNode payload) {
        List<JsonNode> roots = extractRoots(payload);
        var output = new ArrayList<ConvertedFunction>();
        int[] order = {0};
        for (JsonNode root : roots) {
            visit(root, null, List.of(), 1, output, order);
        }
        return output;
    }

    private void visit(
            JsonNode node,
            String parentVendorId,
            List<String> parentPath,
            int depth,
            List<ConvertedFunction> output,
            int[] order
    ) {
        String name = firstNonBlank(firstText(node, "name", "functionName", "function_name", "title"),
                "Unnamed Function " + (order[0] + 1));
        var path = new ArrayList<>(parentPath);
        path.add(name);
        String vendorId = firstNonBlank(firstText(node, "id", "functionId", "function_id", "code"),
                "function-" + sha256(String.join("/", path)).substring(0, 16));
        JsonNode featuresNode = node.path("features");
        List<Map<String, Object>> features = featureList(featuresNode);
        var capabilities = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < features.size(); index++) {
            capabilities.add(featureCapability(features.get(index), vendorId, index));
        }
        var keywords = new LinkedHashSet<String>();
        keywords.add(name);
        keywords.addAll(path);
        features.forEach(feature -> keywords.add(firstNonBlank(
                mapText(feature, "DESC", "desc", "description", "sources"), "")));
        var allowedLayers = new LinkedHashSet<String>();
        capabilities.forEach(capability -> {
            @SuppressWarnings("unchecked")
            var layers = (List<String>) capability.get("allowedLayers");
            allowedLayers.addAll(layers);
        });
        int level = node.path("level").canConvertToInt() ? node.path("level").asInt() : depth;
        order[0]++;
        output.add(new ConvertedFunction(vendorId, parentVendorId, level, name,
                buildDescription(node, features), String.join(" > ", path), order[0],
                automationLimited(node, features), mapper.convertValue(featuresNode, Object.class),
                capabilities, Map.of("keywords", keywords.stream().filter(StringUtils::hasText).toList(),
                "allowedLayers", List.copyOf(allowedLayers)),
                mapper.convertValue(node, Map.class)));
        JsonNode children = node.path("children");
        if (children.isArray()) {
            children.forEach(child -> {
                if (child.isObject()) {
                    visit(child, vendorId, path, depth + 1, output, order);
                }
            });
        }
    }

    private List<JsonNode> extractRoots(JsonNode payload) {
        if (payload.isArray()) {
            var roots = new ArrayList<JsonNode>();
            payload.forEach(item -> {
                if (item.isObject()) {
                    roots.add(item);
                }
            });
            return roots;
        }
        if (!firstText(payload, "name", "functionName", "function_name", "title").isBlank()) {
            return List.of(payload);
        }
        for (String key : List.of("tree", "functions", "nodes", "children", "data")) {
            if (payload.has(key)) {
                return extractRoots(payload.get(key));
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> featureList(JsonNode features) {
        if (features == null || features.isMissingNode() || features.isNull()) {
            return List.of();
        }
        if (features.isArray()) {
            var result = new ArrayList<Map<String, Object>>();
            features.forEach(item -> result.add(item.isObject()
                    ? mapper.convertValue(item, Map.class) : Map.of("DESC", item.asText())));
            return result;
        }
        if (features.isObject()) {
            return List.of(mapper.convertValue(features, Map.class));
        }
        return List.of(Map.of("DESC", features.asText()));
    }

    private Map<String, Object> featureCapability(Map<String, Object> feature, String vendorId, int index) {
        String description = firstNonBlank(mapText(feature, "DESC", "desc", "description", "sources"),
                "Feature " + (index + 1));
        String featureType = mapText(feature, "type", "TYPE");
        String layer = inferLayer(featureType + " " + description);
        return Map.of(
                "capabilityCode", vendorId + ".feature." + (index + 1),
                "name", description.substring(0, Math.min(255, description.length())),
                "required", !truthy(firstMapValue(feature, "gap", "GAP")),
                "allowedLayers", List.of(layer),
                "allowedActionTypes", inferActionTypes(description),
                "source", value(firstMapValue(feature, "sources", "source")),
                "check", value(firstMapValue(feature, "check", "CHECK")),
                "gap", value(firstMapValue(feature, "gap", "GAP")),
                "fault", value(firstMapValue(feature, "fault", "FAULT"))
        );
    }

    private String inferLayer(String text) {
        String lowered = text.toLowerCase(Locale.ROOT);
        if (containsAny(lowered, "popup", "dialog", "modal", "弹窗", "浮层", "面板", "抽屉")) {
            return "popupAction";
        }
        if (containsAny(lowered, "external", "system", "sdk", "camera", "系统", "相机", "相册", "第三方", "小程序")) {
            return "externalAction";
        }
        if (containsAny(lowered, "state", "toggle", "like", "follow", "状态", "点赞", "收藏", "关注", "开关")) {
            return "stateAction";
        }
        return "pageNaviAction";
    }

    private List<String> inferActionTypes(String text) {
        String lowered = text.toLowerCase(Locale.ROOT);
        var result = new ArrayList<String>();
        if (containsAny(lowered, "swipe", "scroll", "滑动", "滚动")) result.add("swipe");
        if (containsAny(lowered, "long press", "long_press", "长按")) result.add("longPress");
        if (containsAny(lowered, "input", "search", "输入", "搜索")) result.add("input");
        return result.isEmpty() ? List.of("tap") : result;
    }

    private boolean automationLimited(JsonNode node, List<Map<String, Object>> features) {
        String text = (node + " " + features).toLowerCase(Locale.ROOT);
        boolean fault = features.stream().anyMatch(item -> truthy(firstMapValue(item, "fault", "FAULT")));
        return fault || containsAny(text, "face", "biometric", "payment", "permission",
                "人脸", "生物识别", "支付", "权限", "验证码", "实名");
    }

    private String buildDescription(JsonNode node, List<Map<String, Object>> features) {
        var values = new LinkedHashSet<String>();
        String nodeDescription = firstText(node, "description", "DESC", "desc");
        if (!nodeDescription.isBlank()) values.add(nodeDescription);
        features.stream().map(item -> mapText(item, "DESC", "desc", "description"))
                .filter(StringUtils::hasText).forEach(values::add);
        return String.join("；", values);
    }

    private UUID findOrCreateApp(String appName, JsonNode metadata, String source) {
        var ids = jdbc.query("SELECT app_id FROM apps WHERE lower(app_name) = lower(:name) LIMIT 1",
                Map.of("name", appName), (rs, row) -> rs.getObject(1, UUID.class));
        if (!ids.isEmpty()) return ids.getFirst();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apps (app_id, package_name, app_name, platform, vendor)
                VALUES (:id, :packageName, :appName, :platform, :vendor)
                """, Map.of("id", id,
                "packageName", "vendor.function-tree." + sha256(appName).substring(0, 12),
                "appName", appName,
                "platform", firstNonBlank(firstText(metadata, "platform"), "android"),
                "vendor", firstNonBlank(firstText(metadata, "vendor"), source)));
        return id;
    }

    private List<Map<String, Object>> buildFunctionTree(List<Map<String, Object>> rows) {
        var nodes = new LinkedHashMap<UUID, Map<String, Object>>();
        var children = new HashMap<UUID, List<Map<String, Object>>>();
        for (var row : rows) {
            UUID id = (UUID) row.get("function_id");
            var node = camelize(row);
            node.put("children", new ArrayList<>());
            nodes.put(id, node);
        }
        var roots = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            UUID id = (UUID) row.get("function_id");
            UUID parent = (UUID) row.get("parent_function_id");
            if (parent == null || !nodes.containsKey(parent)) {
                roots.add(nodes.get(id));
            } else {
                @SuppressWarnings("unchecked")
                var list = (List<Map<String, Object>>) nodes.get(parent).get("children");
                list.add(nodes.get(id));
            }
        }
        return roots;
    }

    private Set<String> tokens(String value) {
        String lowered = value(value).toLowerCase(Locale.ROOT);
        var result = new LinkedHashSet<String>();
        var latin = LATIN_TOKEN.matcher(lowered);
        while (latin.find()) result.add(latin.group());
        var chinese = CHINESE_CHUNK.matcher(lowered);
        while (chinese.find()) {
            String chunk = chinese.group();
            result.add(chunk);
            for (int index = 0; index + 1 < chunk.length(); index++) {
                result.add(chunk.substring(index, index + 2));
            }
        }
        result.removeIf(token -> token.length() <= 1);
        return result;
    }

    private double coverage(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        int matches = 0;
        for (String token : left) if (right.contains(token)) matches++;
        return matches / (double) Math.max(1, Math.min(left.size(), right.size()));
    }

    private double trigramSimilarity(String left, String right) {
        Set<String> a = ngrams(value(left), 3);
        Set<String> b = ngrams(value(right), 3);
        if (a.isEmpty() || b.isEmpty()) return 0;
        int matches = 0;
        for (String gram : a) if (b.contains(gram)) matches++;
        return (2.0 * matches) / (a.size() + b.size());
    }

    private Set<String> ngrams(String value, int size) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        var result = new HashSet<String>();
        for (int index = 0; index + size <= Math.min(normalized.length(), 500); index++) {
            result.add(normalized.substring(index, index + size));
        }
        return result;
    }

    private List<String> intersection(Set<String> left, Set<String> right) {
        return left.stream().filter(right::contains).sorted().limit(30).toList();
    }

    private double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }

    private String capabilityRole(String layer) {
        return switch (value(layer)) {
            case "pageNaviAction" -> "entry";
            case "externalAction" -> "exit";
            default -> "behavior";
        };
    }

    private List<Map<String, Object>> camelizeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::camelize).toList();
    }

    private Map<String, Object> camelize(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach((key, value) -> {
            StringBuilder camel = new StringBuilder();
            boolean upper = false;
            for (char character : key.toCharArray()) {
                if (character == '_') {
                    upper = true;
                } else {
                    camel.append(upper ? Character.toUpperCase(character) : character);
                    upper = false;
                }
            }
            if (value instanceof org.postgresql.util.PGobject object
                    && "jsonb".equals(object.getType())) {
                String raw = object.getValue();
                value = raw != null && raw.stripLeading().startsWith("[") ? json.array(raw) : json.object(raw);
            }
            result.put(camel.toString(), value);
        });
        return result;
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node != null && node.has(key) && !node.get(key).isNull()) {
                String value = node.get(key).asText("").trim();
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private String mapText(Map<String, Object> source, String... keys) {
        return value(firstMapValue(source, keys));
    }

    private Object firstMapValue(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) return source.get(key);
            for (var entry : source.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
            }
        }
        return null;
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) return bool;
        String text = value(value).trim().toLowerCase(Locale.ROOT);
        return !text.isBlank() && !Set.of("false", "0", "no", "none", "null").contains(text);
    }

    private boolean containsAny(String value, String... needles) {
        return Arrays.stream(needles).anyMatch(value::contains);
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values).filter(StringUtils::hasText).findFirst().orElse("");
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record ConvertedFunction(
            String vendorId,
            String parentVendorId,
            int level,
            String name,
            String description,
            String path,
            int displayOrder,
            boolean automationLimited,
            Object features,
            List<Map<String, Object>> expectedCapabilities,
            Map<String, Object> matchRules,
            Map<String, Object> raw
    ) {
    }

    private record Document(
            UUID id,
            UUID parentId,
            String name,
            String text,
            Set<String> tokens,
            String layer,
            Map<String, Object> metadata
    ) {
    }

    private record Scored(UUID functionId, double score, Map<String, Object> evidence) {
    }

    private record Decision(
            Scored scored,
            String status,
            String source,
            double secondBest,
            double margin,
            boolean primary,
            UUID inheritedFrom
    ) {
    }

    private record Context(
            UUID appId,
            List<Document> functions,
            List<Document> pages,
            List<Document> actions
    ) {
    }
}
