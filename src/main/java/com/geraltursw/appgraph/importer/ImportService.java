package com.geraltursw.appgraph.importer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.geraltursw.appgraph.common.JsonSupport;
import com.geraltursw.appgraph.config.AppGraphProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ImportService {
    private static final List<String> ACTION_LAYERS =
            List.of("popupAction", "stateAction", "externalAction", "pageNaviAction");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JsonSupport json;
    private final Path storageRoot;

    public ImportService(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            JsonSupport json,
            AppGraphProperties properties
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.json = json;
        this.storageRoot = properties.storageRoot().toAbsolutePath().normalize();
    }

    @Transactional
    public Map<String, Object> scanFolder(String folderValue) {
        try {
            Path folder = Path.of(folderValue).toAbsolutePath().normalize();
            Path resultFile = Files.isRegularFile(folder)
                    ? folder
                    : folder.resolve("ai_result.json");
            if (!Files.isRegularFile(resultFile)) {
                throw new IllegalArgumentException("ai_result.json was not found: " + resultFile);
            }
            JsonNode payload = mapper.readTree(Files.readAllBytes(resultFile));
            JsonNode appNode = payload.path("app");
            String appName = firstText(appNode, "appName", "app_name", "name");
            String packageName = firstText(appNode, "packageName", "package_name", "package");
            if (appName.isBlank()) {
                throw new IllegalArgumentException("app.appName is required");
            }
            if (packageName.isBlank()) {
                packageName = "imported." + sha256(appName).substring(0, 16);
            }
            UUID appId = upsertApp(appNode, appName, packageName);
            jdbc.queryForList("SELECT app_id FROM apps WHERE app_id=:id FOR UPDATE",Map.of("id",appId));
            UUID scanId = createScan(appId, payload.path("scan"));
            Files.createDirectories(storageRoot);

            Map<String, UUID> pageInstanceBySourceId = new HashMap<>();
            Map<String, UUID> canonicalBySourceId = new HashMap<>();
            int createdPages = 0;
            int reusedPages = 0;
            int pageInstances = 0;
            JsonNode pages = payload.path("pages");
            if (pages.isArray()) {
                for (int index = 0; index < pages.size(); index++) {
                    JsonNode page = pages.get(index);
                    String sourceId = firstNonBlank(
                            firstText(page, "id", "pageId", "page_id", "localId", "local_id"),
                            "page-" + index
                    );
                    String title = firstNonBlank(firstText(page, "pageTitle", "page_title", "title"),
                            "未命名页面");
                    String pageType = firstNonBlank(firstText(page, "pageType", "page_type"), "unknown");
                    String structureHash = firstNonBlank(
                            firstText(page, "structureHash", "structure_hash"),
                            deriveStructureHash(page)
                    );
                    UUID canonicalId = findCanonical(appId, structureHash);
                    if (canonicalId == null) {
                        canonicalId = UUID.randomUUID();
                        String pageHashId = sha256(appId + "|" + structureHash).substring(0, 32);
                        jdbc.update("""
                                INSERT INTO canonical_pages (
                                    canonical_page_id, app_id, canonical_page_key, page_hash_id,
                                    display_name, page_type, primary_structure_hash,
                                    instance_count, review_status
                                ) VALUES (
                                    :id, :appId, :key, :pageHashId,
                                    :title, :pageType, :structureHash, 1, 'pending'
                                )
                                """, new MapSqlParameterSource()
                                .addValue("id", canonicalId).addValue("appId", appId)
                                .addValue("key", "page-" + structureHash.substring(0, Math.min(16, structureHash.length())))
                                .addValue("pageHashId", pageHashId).addValue("title", title)
                                .addValue("pageType", pageType).addValue("structureHash", structureHash));
                        createdPages++;
                    } else {
                        jdbc.update("""
                                UPDATE canonical_pages
                                SET instance_count = instance_count + 1, updated_at = now()
                                WHERE canonical_page_id = :id
                                """, Map.of("id", canonicalId));
                        reusedPages++;
                    }

                    UUID pageInstanceId = UUID.randomUUID();
                    List<String> images = importImages(page, resultFile.getParent(), appName);
                    Map<String, Object> action = normalizeAction(page.path("action").isMissingNode()
                            ? page.path("pageActions") : page.path("action"));
                    String raw = mapper.writeValueAsString(page);
                    jdbc.update("""
                            INSERT INTO page_instances (
                                page_instance_id, scan_id, app_id, canonical_page_id, page_title,
                                page_type, screenshot_hash, visual_hash, structure_hash, route_hash,
                                ocr_text, ai_summary, inferred_purpose, page_url, embedding_text, images, action,
                                ai_inference, ai_recursive, confidence, raw_ai_payload, normalized_payload
                            ) VALUES (
                                :id, :scanId, :appId, :canonicalId, :title,
                                :pageType, :screenshotHash, :visualHash, :structureHash, :routeHash,
                                :ocrText, :summary, :purpose, :pageUrl, :embedding, :images::jsonb, :action::jsonb,
                                :inference::jsonb, :recursive, :confidence, :raw::jsonb, :normalized::jsonb
                            )
                            """, new MapSqlParameterSource()
                            .addValue("id", pageInstanceId).addValue("scanId", scanId).addValue("appId", appId)
                            .addValue("canonicalId", canonicalId).addValue("title", title)
                            .addValue("pageType", pageType)
                            .addValue("screenshotHash", nullable(firstText(page, "screenshotHash", "screenshot_hash")))
                            .addValue("visualHash", nullable(firstText(page, "visualHash", "visual_hash")))
                            .addValue("structureHash", structureHash)
                            .addValue("routeHash", nullable(firstText(page, "routeHash", "route_hash")))
                            .addValue("ocrText", nullable(firstText(page, "ocrText", "ocr_text")))
                            .addValue("summary", nullable(firstText(page, "pageText", "page_text", "aiSummary", "ai_summary")))
                            .addValue("purpose", nullable(firstText(page, "inferredPurpose", "inferred_purpose")))
                            .addValue("pageUrl", nullable(firstText(page, "pageUrl", "page_url", "url")))
                            .addValue("embedding",nullable(firstText(page,"embeddingText","embedding_text")))
                            .addValue("images", json.write(images)).addValue("action", json.write(action))
                            .addValue("inference", json.write(nodeObject(page.path("aiInference").isMissingNode()
                                    ? page.path("ai_inference") : page.path("aiInference"))))
                            .addValue("recursive", page.path("aiRecursive").asBoolean(
                                    page.path("ai_recursive").asBoolean(false)))
                            .addValue("confidence", page.path("confidence").isNumber()
                                    ? page.path("confidence").asDouble() : null)
                            .addValue("raw", raw).addValue("normalized", raw));
                    persistActions(appId, canonicalId, pageInstanceId, action);
                    pageInstanceBySourceId.put(sourceId, pageInstanceId);
                    canonicalBySourceId.put(sourceId, canonicalId);
                    pageInstances++;
                }
            }

            int edgeCount = importEdges(payload.path("edges"), scanId, appId,
                    pageInstanceBySourceId, canonicalBySourceId);
            jdbc.update("""
                    UPDATE scans SET status = 'completed', finished_at = now()
                    WHERE scan_id = :scanId
                    """, Map.of("scanId", scanId));
            return Map.of(
                    "status", "success", "appId", appId, "scanId", scanId,
                    "createdCanonicalPages", createdPages, "reusedCanonicalPages", reusedPages,
                    "pageInstances", pageInstances, "edges", edgeCount
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to import AI result: " + exception.getMessage(), exception);
        }
    }

    private UUID upsertApp(JsonNode node, String appName, String packageName) {
        var ids = jdbc.query("SELECT app_id FROM apps WHERE package_name = :packageName",
                Map.of("packageName", packageName), (rs, row) -> rs.getObject(1, UUID.class));
        if (!ids.isEmpty()) return ids.getFirst();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apps (
                    app_id, package_name, app_name, market_rank, category, platform, vendor
                ) VALUES (
                    :id, :packageName, :appName, :marketRank, :category, :platform, :vendor
                )
                """, new MapSqlParameterSource().addValue("id", id).addValue("packageName", packageName)
                .addValue("appName", appName)
                .addValue("marketRank", node.path("marketRank").isInt()
                        ? node.path("marketRank").asInt() : null)
                .addValue("category", nullable(firstText(node, "category")))
                .addValue("platform", firstNonBlank(firstText(node, "platform"), "android"))
                .addValue("vendor", nullable(firstText(node, "vendor"))));
        return id;
    }

    private UUID createScan(UUID appId, JsonNode node) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO scans (
                    scan_id, app_id, app_version, device_id, platform, os_version,
                    script_version, ai_model, status, scan_config
                ) VALUES (
                    :id, :appId, :appVersion, :deviceId, :platform, :osVersion,
                    :scriptVersion, :aiModel, 'importing', :config::jsonb
                )
                """, new MapSqlParameterSource().addValue("id", id).addValue("appId", appId)
                .addValue("appVersion", nullable(firstText(node, "appVersion", "app_version")))
                .addValue("deviceId", nullable(firstText(node, "deviceId", "device_id")))
                .addValue("platform", firstNonBlank(firstText(node, "platform"), "android"))
                .addValue("osVersion", nullable(firstText(node, "osVersion", "os_version")))
                .addValue("scriptVersion", nullable(firstText(node, "scriptVersion", "script_version")))
                .addValue("aiModel", nullable(firstText(node, "aiModel", "ai_model")))
                .addValue("config", node.isObject() ? node.toString() : "{}"));
        return id;
    }

    private UUID findCanonical(UUID appId, String structureHash) {
        var ids = jdbc.query("""
                SELECT canonical_page_id FROM canonical_pages
                WHERE app_id = :appId AND primary_structure_hash = :hash LIMIT 1
                """, Map.of("appId", appId, "hash", structureHash),
                (rs, row) -> rs.getObject(1, UUID.class));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private List<String> importImages(JsonNode page, Path scanFolder, String appName) throws Exception {
        var paths = new ArrayList<String>();
        JsonNode images = page.path("images");
        if (images.isArray()) images.forEach(item -> paths.add(item.asText()));
        String screenshot = firstText(page, "screenshot", "screenshotPath", "screenshot_path", "imageUrl", "image_url");
        if (!screenshot.isBlank()) paths.addFirst(screenshot);
        var stored = new ArrayList<String>();
        for (String source : paths) {
            if (!StringUtils.hasText(source)) continue;
            Path candidate = Path.of(source);
            if (!candidate.isAbsolute()) {
                candidate = scanFolder.resolve(source).normalize();
                if (!Files.isRegularFile(candidate)) {
                    candidate = scanFolder.resolve("screenshots").resolve(Path.of(source).getFileName()).normalize();
                }
            }
            if (Files.isRegularFile(candidate)) {
                String name = sha256(appName + "|" + candidate.toAbsolutePath()).substring(0, 12)
                        + "_" + candidate.getFileName();
                Files.copy(candidate, storageRoot.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                stored.add(name);
            } else if (source.startsWith("http://") || source.startsWith("https://")) {
                stored.add(source);
            }
        }
        return stored.stream().distinct().toList();
    }

    private int importEdges(
            JsonNode edges,
            UUID scanId,
            UUID appId,
            Map<String, UUID> instanceIds,
            Map<String, UUID> canonicalIds
    ) {
        if (!edges.isArray()) return 0;
        int count = 0;
        for (JsonNode edge : edges) {
            String from = firstText(edge, "from", "source", "fromPageId", "from_page_id");
            String to = firstText(edge, "to", "target", "toPageId", "to_page_id");
            if (!canonicalIds.containsKey(from) || !canonicalIds.containsKey(to)) continue;
            jdbc.update("""
                    INSERT INTO page_edges (
                        edge_id, scan_id, app_id, from_page_instance_id, to_page_instance_id,
                        from_canonical_page_id, to_canonical_page_id, action_type, label,
                        widget_description, confidence, status, raw_action_payload
                    ) VALUES (
                        :id, :scanId, :appId, :fromInstance, :toInstance,
                        :fromPage, :toPage, :actionType, :label,
                        :widgetDescription, :confidence, 'discovered', :raw::jsonb
                    )
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                    .addValue("scanId", scanId).addValue("appId", appId)
                    .addValue("fromInstance", instanceIds.get(from)).addValue("toInstance", instanceIds.get(to))
                    .addValue("fromPage", canonicalIds.get(from)).addValue("toPage", canonicalIds.get(to))
                    .addValue("actionType", firstNonBlank(firstText(edge, "actionType", "action_type"), "tap"))
                    .addValue("label", firstNonBlank(firstText(edge, "label", "widgetDescription",
                            "widget_description"), "页面跳转"))
                    .addValue("widgetDescription", nullable(firstText(edge, "widgetDescription", "widget_description")))
                    .addValue("confidence", edge.path("confidence").isNumber()
                            ? edge.path("confidence").asDouble() : null)
                    .addValue("raw", edge.toString()));
            count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private void persistActions(
            UUID appId,
            UUID canonicalId,
            UUID pageInstanceId,
            Map<String, Object> actions
    ) {
        for (String layer : ACTION_LAYERS) {
            var items = (List<Map<String, Object>>) actions.get(layer);
            for (Map<String, Object> item : items) {
                String name = firstNonBlank(mapText(item, "label", "btn", "name", "semanticName"), "未命名动作");
                String type = firstNonBlank(mapText(item, "actionType", "type"), "tap");
                String fingerprint = sha256(canonicalId + "|" + layer + "|" + type + "|" + name);
                jdbc.update("""
                        INSERT INTO page_actions (
                            action_id, app_id, canonical_page_id, page_instance_id,
                            action_fingerprint, action_layer, action_type, semantic_name,
                            description, target, parameters, expected_effect, effect_status,
                            confidence, raw_payload
                        ) VALUES (
                            :id, :appId, :pageId, :instanceId,
                            :fingerprint, :layer, :type, :name,
                            :description, :target::jsonb, :parameters::jsonb, :effect::jsonb, 'predicted',
                            :confidence, :raw::jsonb
                        )
                        ON CONFLICT (canonical_page_id, action_fingerprint)
                        DO UPDATE SET raw_payload = EXCLUDED.raw_payload, updated_at = now()
                        """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                        .addValue("appId", appId).addValue("pageId", canonicalId)
                        .addValue("instanceId", pageInstanceId).addValue("fingerprint", fingerprint)
                        .addValue("layer", layer).addValue("type", type).addValue("name", name)
                        .addValue("description", mapText(item, "description"))
                        .addValue("target", json.write(json.object(item.get("target"))))
                        .addValue("parameters", json.write(json.object(item.get("parameters"))))
                        .addValue("effect", json.write(json.object(item.get("expectedEffect"))))
                        .addValue("confidence", item.get("confidence")).addValue("raw", json.write(item)));
            }
        }
    }

    private Map<String, Object> normalizeAction(JsonNode action) {
        var result = new LinkedHashMap<String, Object>();
        for (String layer : ACTION_LAYERS) {
            var items = new ArrayList<Map<String, Object>>();
            JsonNode values = action.path(layer);
            if (values.isArray()) values.forEach(item -> items.add(nodeObject(item)));
            result.put(layer, items);
        }
        return result;
    }

    private Map<String, Object> nodeObject(JsonNode node) {
        return node != null && node.isObject() ? mapper.convertValue(node, Map.class) : new LinkedHashMap<>();
    }

    private String deriveStructureHash(JsonNode page) {
        JsonNode stable = !page.path("normalizedLayout").isMissingNode()
                ? page.path("normalizedLayout")
                : !page.path("normalized_layout").isMissingNode()
                ? page.path("normalized_layout")
                : page.path("widgets");
        String source = stable.isMissingNode() || stable.isNull()
                ? firstText(page, "pageType", "page_type", "pageTitle", "page_title")
                : stable.toString();
        return sha256(source);
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

    private String mapText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) return String.valueOf(map.get(key));
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value;
        return "";
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String nullable(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
