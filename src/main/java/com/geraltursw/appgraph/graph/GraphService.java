package com.geraltursw.appgraph.graph;

import com.geraltursw.appgraph.common.ConflictException;
import com.geraltursw.appgraph.common.JsonSupport;
import com.geraltursw.appgraph.common.NotFoundException;
import com.geraltursw.appgraph.config.AppGraphProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class GraphService {
    private static final List<String> ACTION_LAYERS =
            List.of("popupAction", "stateAction", "externalAction", "pageNaviAction");

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonSupport json;
    private final Path storageRoot;
    private final GraphGovernanceService governance;

    public GraphService(NamedParameterJdbcTemplate jdbc, JsonSupport json, AppGraphProperties properties, GraphGovernanceService governance) {
        this.jdbc = jdbc;
        this.json = json;
        this.governance = governance;
        this.storageRoot = properties.storageRoot().toAbsolutePath().normalize();
    }

    public Map<String, Object> appList() {
        var apps = jdbc.query("""
                SELECT a.app_name, count(p.canonical_page_id) AS page_count
                FROM apps a
                LEFT JOIN canonical_pages p ON p.app_id = a.app_id
                GROUP BY a.app_id, a.app_name, a.market_rank
                ORDER BY a.market_rank NULLS LAST, a.app_name
                """, Map.of(), (rs, row) -> Map.<String, Object>of(
                "appName", rs.getString("app_name"),
                "count", rs.getInt("page_count")
        ));
        return Map.of("status", "success", "apps", apps);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String, Object> queryGraph(String appName) {
        UUID appId = findAppId(appName);
        var rows = jdbc.query("""
                SELECT p.canonical_page_id, p.page_hash_id, p.display_name, p.page_type,
                       p.primary_structure_hash, p.review_status,
                       i.ai_summary, i.page_url, i.images, i.action, i.ai_inference,
                       i.ai_recursive, i.raw_ai_payload, i.embedding_text
                FROM canonical_pages p
                LEFT JOIN LATERAL (
                    SELECT *
                    FROM page_instances pi
                    WHERE pi.canonical_page_id = p.canonical_page_id
                    ORDER BY pi.created_at DESC
                    LIMIT 1
                ) i ON true
                WHERE p.app_id = :appId
                ORDER BY p.created_at, p.canonical_page_id
                """, Map.of("appId", appId), (rs, rowNum) -> {
            var node = new LinkedHashMap<String, Object>();
            node.put("_canonicalId", rs.getObject("canonical_page_id", UUID.class));
            node.put("id", rowNum + 1);
            node.put("pageId", rs.getString("page_hash_id"));
            node.put("pageTitle", rs.getString("display_name"));
            node.put("pageText", value(rs.getString("ai_summary")));
            node.put("embeddingText", value(rs.getString("embedding_text")));
            node.put("images", json.array(rs.getString("images")));
            node.put("pageUrl", value(rs.getString("page_url")));
            node.put("aiInference", json.object(rs.getString("ai_inference")));
            node.put("aiRecursive", rs.getBoolean("ai_recursive"));
            node.put("action", normalizeAction(json.object(rs.getString("action"))));
            node.put("_hasPersistedAction", rs.getString("action") != null);
            var pageInfo = new LinkedHashMap<String, Object>();
            pageInfo.put("pageType", rs.getString("page_type"));
            pageInfo.put("structureHash", rs.getString("primary_structure_hash"));
            pageInfo.put("reviewStatus", rs.getString("review_status"));
            pageInfo.putAll(extractPageInfo(rs.getString("raw_ai_payload")));
            node.put("pageInfo", pageInfo);
            return node;
        });

        if (rows.isEmpty()) {
            return Map.of("roots", List.of(), "orphanPages", List.of());
        }

        var byId = new LinkedHashMap<UUID, Map<String, Object>>();
        rows.forEach(node -> byId.put((UUID) node.get("_canonicalId"), node));
        var children = new LinkedHashMap<UUID, List<UUID>>();
        var incoming = new HashSet<UUID>();

        var edges = jdbc.queryForList("""
                SELECT edge_id, from_canonical_page_id, to_canonical_page_id,
                       action_type, label, widget_description
                FROM page_edges
                WHERE app_id = :appId
                  AND from_canonical_page_id IS NOT NULL
                  AND to_canonical_page_id IS NOT NULL
                ORDER BY created_at
                """, Map.of("appId", appId));
        for (var edge : edges) {
            UUID from = (UUID) edge.get("from_canonical_page_id");
            UUID to = (UUID) edge.get("to_canonical_page_id");
            if (!byId.containsKey(from) || !byId.containsKey(to)) {
                continue;
            }
            children.computeIfAbsent(from, ignored -> new ArrayList<>()).add(to);
            incoming.add(to);
            String widget = value(edge.get("widget_description"));
            if (!widget.isBlank()) {
                byId.get(to).put("widgetDescription", widget);
            }
            if (!Boolean.TRUE.equals(byId.get(from).get("_hasPersistedAction"))) {
                @SuppressWarnings("unchecked")
                var action = (Map<String, List<Map<String, Object>>>) byId.get(from).get("action");
                action.get("pageNaviAction").add(Map.of(
                        "id", String.valueOf(edge.get("edge_id")),
                        "label", widget.isBlank() ? value(edge.get("label")) : widget,
                        "actionType", value(edge.get("action_type")),
                        "description", widget.isBlank() ? value(edge.get("label")) : widget,
                        "targetPageId", value(byId.get(to).get("pageId")),
                        "targetPageTitle", value(byId.get(to).get("pageTitle"))
                ));
            }
        }

        var roots = new ArrayList<Map<String, Object>>();
        var orphanPages = new ArrayList<Map<String, Object>>();
        var reached=governance.reachable(appId);
        var entryIds=jdbc.queryForList("SELECT page_id FROM graph_entry_points WHERE app_id=:id",Map.of("id",appId),UUID.class);
        var expanded = new HashSet<UUID>();
        for(var id:entryIds) if(byId.containsKey(id)) roots.add(buildTree(id,byId,children,new LinkedHashSet<>(),expanded));
        var orphanChildren=new LinkedHashMap<UUID,List<UUID>>();var orphanIncoming=new HashSet<UUID>();
        children.forEach((from,tos)->{if(!reached.contains(from)){var filtered=tos.stream().filter(to->!reached.contains(to)).toList();orphanChildren.put(from,filtered);orphanIncoming.addAll(filtered);}});
        for(var id:byId.keySet()) if(!reached.contains(id)&&!orphanIncoming.contains(id)) orphanPages.add(buildTree(id,byId,orphanChildren,new LinkedHashSet<>(),expanded));
        var relations = edges.stream().filter(e -> byId.containsKey(e.get("from_canonical_page_id")) && byId.containsKey(e.get("to_canonical_page_id")))
                .map(e -> Map.of("id", e.get("edge_id").toString(),
                        "fromPageId", byId.get(e.get("from_canonical_page_id")).get("pageId"),
                        "toPageId", byId.get(e.get("to_canonical_page_id")).get("pageId"),
                        "widgetDescription", value(e.get("widget_description")),
                        "label", value(e.get("label")), "actionType", value(e.get("action_type"))))
                .toList();
        return Map.of("roots", roots, "orphanPages", orphanPages,"edges",relations,"graphVersion",governance.version(appId));
    }

    @Transactional
    public Map<String, Object> createOrphan(GraphRequests.CreateOrphanNode request) {
        UUID appId = findAppId(request.appName().trim());
        var existing = jdbc.queryForList("""
                SELECT p.page_hash_id, p.display_name, i.page_url
                FROM page_instances i
                JOIN canonical_pages p ON p.canonical_page_id = i.canonical_page_id
                WHERE i.app_id = :appId AND i.page_url = :pageUrl
                ORDER BY i.created_at DESC LIMIT 1
                """, Map.of("appId", appId, "pageUrl", request.pageUrl().trim()));
        if (!existing.isEmpty()) {
            throw new ConflictException("This URL already exists in the graph");
        }

        UUID pageId = UUID.randomUUID();
        UUID scanId = latestOrCreateScan(appId, "manual-orphan-create");
        String hashId = sha256("orphan:" + appId + ":" + request.pageUrl()).substring(0, 32);
        var pageInfo = Map.of("pageType", "orphan", "isOrphan", true, "source", "manual",
                "reviewStatus", "draft");
        jdbc.update("""
                INSERT INTO canonical_pages (
                    canonical_page_id, app_id, canonical_page_key, page_hash_id, display_name,
                    page_type, primary_route_hash, instance_count, review_status
                ) VALUES (
                    :id, :appId, :key, :hash, '待探索页面', 'orphan', :routeHash, 1, 'draft'
                )
                """, new MapSqlParameterSource()
                .addValue("id", pageId).addValue("appId", appId)
                .addValue("key", "orphan-" + hashId.substring(0, 12)).addValue("hash", hashId)
                .addValue("routeHash", sha256(request.pageUrl())));
        jdbc.update("""
                INSERT INTO page_instances (
                    page_instance_id, scan_id, app_id, canonical_page_id, page_title, page_type,
                    ai_summary, inferred_purpose, page_url, images, action, ai_inference,
                    ai_recursive, raw_ai_payload, normalized_payload
                ) VALUES (
                    :instanceId, :scanId, :appId, :pageId, '待探索页面', 'orphan',
                    '人工创建的游离 URL 页面，等待 AI 探索和截图识别。', '等待 AI 探索', :pageUrl,
                    '[]'::jsonb, :action::jsonb, :inference::jsonb, false, :raw::jsonb, :normalized::jsonb
                )
                """, new MapSqlParameterSource()
                .addValue("instanceId", UUID.randomUUID()).addValue("scanId", scanId)
                .addValue("appId", appId).addValue("pageId", pageId).addValue("pageUrl", request.pageUrl().trim())
                .addValue("action", json.write(emptyAction()))
                .addValue("inference", json.write(Map.of("label", "待探索", "reason", "该 URL 尚未归入主图谱。")))
                .addValue("raw", json.write(Map.of("pageUrl", request.pageUrl().trim(), "images", List.of(),
                        "pageInfo", pageInfo)))
                .addValue("normalized", json.write(Map.of("pageInfo", pageInfo))));
        return Map.of("status", "success", "created", true, "pageId", hashId);
    }

    @Transactional
    public Map<String, Object> moveNode(GraphRequests.MoveNode request) {
        var page = findPage(request.pageId());
        var parent = findPage(request.newParentId());
        UUID pageId = (UUID) page.get("canonical_page_id");
        UUID parentId = (UUID) parent.get("canonical_page_id");
        UUID appId = (UUID) page.get("app_id");
        jdbc.queryForList("SELECT app_id FROM apps WHERE app_id=:id FOR UPDATE",Map.of("id",appId));
        if (pageId.equals(parentId)) {
            throw new ConflictException("A node cannot be its own parent");
        }
        if (!appId.equals(parent.get("app_id"))) {
            throw new ConflictException("Nodes must belong to the same app");
        }
        if (isDescendant(appId, pageId, parentId)) {
            throw new ConflictException("Cannot move a node under its descendant");
        }
        Integer incomingCount=jdbc.queryForObject("SELECT count(*) FROM page_edges WHERE to_canonical_page_id=:id",Map.of("id",pageId),Integer.class);
        if(incomingCount>1) throw new ConflictException("Multiple entry edges exist; use explicit edge editing instead of replacing all entries");
        jdbc.update("DELETE FROM page_edges WHERE to_canonical_page_id = :pageId",
                Map.of("pageId", pageId));
        jdbc.update("""
                INSERT INTO page_edges (
                    edge_id, scan_id, app_id, from_canonical_page_id, to_canonical_page_id,
                    action_type, label, widget_description, status, raw_action_payload
                ) VALUES (
                    :edgeId, :scanId, :appId, :parentId, :pageId,
                    'navigate', '人工调整归类', '人工调整归类', 'confirmed',
                    '{"source":"manualDrag"}'::jsonb
                )
                """, new MapSqlParameterSource()
                .addValue("edgeId", UUID.randomUUID()).addValue("scanId", latestOrCreateScan(appId, "manual-move"))
                .addValue("appId", appId).addValue("parentId", parentId).addValue("pageId", pageId));
        jdbc.update("UPDATE canonical_pages SET review_status = 'edited', updated_at = now() WHERE canonical_page_id = :id",
                Map.of("id", pageId));
        return Map.of("status", "success", "moved", true,
                "pageId", request.pageId(), "newParentId", request.newParentId());
    }

    @Transactional
    public Map<String, Object> deleteNode(GraphRequests.DeleteNode request) {
        var page = findPage(request.id());
        UUID pageId = (UUID) page.get("canonical_page_id");
        jdbc.update("UPDATE test_cases SET start_page_id = NULL WHERE start_page_id = :id", Map.of("id", pageId));
        jdbc.update("UPDATE test_cases SET terminal_page_id = NULL WHERE terminal_page_id = :id", Map.of("id", pageId));
        jdbc.update("DELETE FROM function_page_bindings WHERE canonical_page_id = :id", Map.of("id", pageId));
        jdbc.update("DELETE FROM function_action_bindings WHERE action_id IN (SELECT action_id FROM page_actions WHERE canonical_page_id = :id)",
                Map.of("id", pageId));
        jdbc.update("DELETE FROM page_actions WHERE canonical_page_id = :id", Map.of("id", pageId));
        jdbc.update("DELETE FROM page_edges WHERE from_canonical_page_id = :id OR to_canonical_page_id = :id",
                Map.of("id", pageId));
        jdbc.update("DELETE FROM embedding_records WHERE owner_id = :id", Map.of("id", pageId));
        jdbc.update("DELETE FROM page_instances WHERE canonical_page_id = :id", Map.of("id", pageId));
        jdbc.update("DELETE FROM canonical_pages WHERE canonical_page_id = :id", Map.of("id", pageId));
        return Map.of("status", "success", "deleted", true, "pageId", request.id());
    }

    @Transactional
    public Map<String, Object> updateNode(
            String pageHashId,
            String pageTitle,
            String pageText,
            String pageUrl,
            String embeddingText,
            String widgetDescription,
            String keepImagesJson,
            String aiInferenceJson,
            String actionJson,
            boolean aiRecursive,
            List<MultipartFile> newImages
    ) {
        var page = findPage(pageHashId);
        UUID canonicalId = (UUID) page.get("canonical_page_id");
        List<String> finalImages = new ArrayList<>();
        for (Object item : json.array(keepImagesJson)) {
            if (StringUtils.hasText(String.valueOf(item))) {
                finalImages.add(Path.of(String.valueOf(item)).getFileName().toString());
            }
        }
        try {
            Files.createDirectories(storageRoot);
            for (MultipartFile upload : newImages == null ? List.<MultipartFile>of() : newImages) {
                String original = Path.of(value(upload.getOriginalFilename()).isBlank()
                        ? "image.bin" : upload.getOriginalFilename()).getFileName().toString();
                String stored = pageHashId.substring(0, Math.min(10, pageHashId.length()))
                        + "_" + UUID.randomUUID().toString().substring(0, 8) + "_" + original;
                upload.transferTo(storageRoot.resolve(stored));
                finalImages.add(stored);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to store uploaded image", exception);
        }

        Map<String, Object> normalizedAction = normalizeAction(json.object(actionJson));
        Map<String, Object> inference = json.object(aiInferenceJson);
        jdbc.update("""
                UPDATE canonical_pages
                SET display_name = :title, review_status = 'edited', updated_at = now()
                WHERE canonical_page_id = :id
                """, Map.of("title", pageTitle, "id", canonicalId));
        int updated = jdbc.update("""
                UPDATE page_instances
                SET page_title = :title, ai_summary = :text, page_url = :url,
                    images = :images::jsonb, action = :action::jsonb,
                    ai_inference = :inference::jsonb, ai_recursive = :recursive,
                    embedding_text = coalesce(:embedding, embedding_text)
                WHERE page_instance_id = (
                    SELECT page_instance_id FROM page_instances
                    WHERE canonical_page_id = :id ORDER BY created_at DESC LIMIT 1
                )
                """, new MapSqlParameterSource()
                .addValue("title", pageTitle).addValue("text", pageText).addValue("url", pageUrl)
                .addValue("embedding",embeddingText,java.sql.Types.VARCHAR)
                .addValue("images", json.write(finalImages)).addValue("action", json.write(normalizedAction))
                .addValue("inference", json.write(inference)).addValue("recursive", aiRecursive)
                .addValue("id", canonicalId));
        if (updated == 0) {
            throw new ConflictException("Page has no page instance to update");
        }
        jdbc.update("""
                UPDATE page_edges SET widget_description = :description
                WHERE edge_id = (
                    SELECT edge_id FROM page_edges
                    WHERE to_canonical_page_id = :id ORDER BY created_at DESC LIMIT 1
                )
                """, Map.of("description", widgetDescription, "id", canonicalId));
        synchronizeActions((UUID) page.get("app_id"), canonicalId, normalizedAction);
        var response = new LinkedHashMap<String, Object>();
        response.put("status", "success");
        response.put("pageId", pageHashId);
        response.put("pageTitle", pageTitle);
        response.put("pageText", pageText);
        response.put("pageUrl", pageUrl);
        if(embeddingText!=null) response.put("embeddingText",embeddingText);
        response.put("widgetDescription", widgetDescription);
        response.put("images", finalImages);
        response.put("action", normalizedAction);
        response.put("aiInference", inference);
        response.put("aiRecursive", aiRecursive);
        response.put("persisted", true);
        return response;
    }

    public ResponseEntity<Resource> image(String imageName) {
        Path imagePath = resolveImage(imageName);
        String contentType;
        try {
            contentType = Files.probeContentType(imagePath);
        } catch (IOException ignored) {
            contentType = null;
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType == null ? "application/octet-stream" : contentType))
                .body(new FileSystemResource(imagePath));
    }

    public ResponseEntity<Resource> thumbnail(String imageName, int requestedWidth) {
        Path source = resolveImage(imageName);
        int width = Math.max(80, Math.min(requestedWidth, 480));
        String cacheName = source.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_")
                + "-w" + width + ".jpg";
        Path cacheDirectory = storageRoot.resolve(".thumbnails").normalize();
        Path cached = cacheDirectory.resolve(cacheName).normalize();
        if (!cached.startsWith(cacheDirectory)) {
            throw new IllegalStateException("Invalid thumbnail cache path");
        }
        try {
            if (!Files.isRegularFile(cached)
                    || Files.getLastModifiedTime(cached).compareTo(Files.getLastModifiedTime(source)) < 0) {
                BufferedImage original = ImageIO.read(source.toFile());
                if (original == null || original.getWidth() <= width) {
                    return image(imageName);
                }
                int height = Math.max(1, Math.round(original.getHeight() * (width / (float) original.getWidth())));
                BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = scaled.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, width, height);
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.drawImage(original, 0, 0, width, height, null);
                } finally {
                    graphics.dispose();
                }
                Files.createDirectories(cacheDirectory);
                Path temporary = cacheDirectory.resolve(cacheName + "." + UUID.randomUUID() + ".tmp");
                if (!ImageIO.write(scaled, "jpg", temporary.toFile())) {
                    Files.deleteIfExists(temporary);
                    return image(imageName);
                }
                Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to generate image thumbnail", exception);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(cached));
    }

    private Path resolveImage(String imageName) {
        String safeName = Path.of(imageName).getFileName().toString();
        Path imagePath = storageRoot.resolve(safeName).normalize();
        if (!imagePath.startsWith(storageRoot) || !Files.isRegularFile(imagePath)) {
            throw new NotFoundException("Image not found");
        }
        return imagePath;
    }

    UUID findAppId(String appName) {
        var ids = jdbc.query("SELECT app_id FROM apps WHERE lower(app_name) = lower(:name) LIMIT 1",
                Map.of("name", appName), (rs, row) -> rs.getObject(1, UUID.class));
        if (ids.isEmpty()) {
            throw new NotFoundException("App '" + appName + "' not found");
        }
        return ids.getFirst();
    }

    private Map<String, Object> findPage(String pageHashId) {
        var rows = jdbc.queryForList("""
                SELECT canonical_page_id, app_id, page_hash_id, display_name, page_type
                FROM canonical_pages WHERE page_hash_id = :pageId
                """, Map.of("pageId", pageHashId));
        if (rows.isEmpty()) {
            throw new NotFoundException("Page not found: " + pageHashId);
        }
        return rows.getFirst();
    }

    private UUID latestOrCreateScan(UUID appId, String source) {
        var scans = jdbc.query("SELECT scan_id FROM scans WHERE app_id = :appId ORDER BY started_at DESC LIMIT 1",
                Map.of("appId", appId), (rs, row) -> rs.getObject(1, UUID.class));
        if (!scans.isEmpty()) {
            return scans.getFirst();
        }
        UUID scanId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO scans (scan_id, app_id, status, script_version, scan_config)
                VALUES (:scanId, :appId, 'manual', :source, :config::jsonb)
                """, new MapSqlParameterSource().addValue("scanId", scanId).addValue("appId", appId)
                .addValue("source", source).addValue("config", json.write(Map.of("source", source))));
        return scanId;
    }

    private boolean isDescendant(UUID appId, UUID pageId, UUID candidate) {
        var rows = jdbc.queryForList("""
                SELECT from_canonical_page_id, to_canonical_page_id
                FROM page_edges WHERE app_id = :appId
                """, Map.of("appId", appId));
        Map<UUID, List<UUID>> children = new HashMap<>();
        for (var row : rows) {
            UUID from = (UUID) row.get("from_canonical_page_id");
            UUID to = (UUID) row.get("to_canonical_page_id");
            if (from != null && to != null) {
                children.computeIfAbsent(from, ignored -> new ArrayList<>()).add(to);
            }
        }
        Deque<UUID> pending = new ArrayDeque<>(children.getOrDefault(pageId, List.of()));
        Set<UUID> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            UUID current = pending.removeFirst();
            if (current.equals(candidate)) {
                return true;
            }
            if (visited.add(current)) {
                pending.addAll(children.getOrDefault(current, List.of()));
            }
        }
        return false;
    }

    private Map<String, Object> buildTree(
            UUID id,
            Map<UUID, Map<String, Object>> nodes,
            Map<UUID, List<UUID>> children,
            Set<UUID> path,
            Set<UUID> expanded
    ) {
        var result = new LinkedHashMap<>(nodes.get(id));
        result.remove("_canonicalId");
        result.remove("_hasPersistedAction");
        if (!path.add(id)) {
            result.put("children", List.of());
            result.put("cycleDetected", true);
            return result;
        }
        // Shared DAG descendants are represented once; edges carry every alternate entry.
        if (!expanded.add(id)) {
            result.put("children", List.of());
            return result;
        }
        var nested = children.getOrDefault(id, List.of()).stream()
                .distinct()
                .map(child -> buildTree(child, nodes, children, new LinkedHashSet<>(path), expanded))
                .toList();
        result.put("children", nested);
        return result;
    }

    private Map<String, Object> extractPageInfo(String rawJson) {
        Object info = json.object(rawJson).get("pageInfo");
        if (info == null) {
            info = json.object(rawJson).get("page_info");
        }
        return json.object(info);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeAction(Map<String, Object> source) {
        var normalized = new LinkedHashMap<String, Object>();
        for (String layer : ACTION_LAYERS) {
            Object raw = source.get(layer);
            var items = new ArrayList<Map<String, Object>>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    items.add(json.object(item));
                }
            }
            normalized.put(layer, items);
        }
        return normalized;
    }

    private Map<String, Object> emptyAction() {
        return normalizeAction(Map.of());
    }

    @SuppressWarnings("unchecked")
    private void synchronizeActions(UUID appId, UUID pageId, Map<String, Object> action) {
        jdbc.update("DELETE FROM page_actions WHERE canonical_page_id = :pageId", Map.of("pageId", pageId));
        for (String layer : ACTION_LAYERS) {
            var items = (List<Map<String, Object>>) action.get(layer);
            for (var item : items) {
                String semanticName = firstText(item, "label", "btn", "name", "description");
                if (semanticName.isBlank()) {
                    semanticName = "未命名动作";
                }
                String actionType = firstText(item, "actionType", "type");
                if (actionType.isBlank()) {
                    actionType = "tap";
                }
                String fingerprint = sha256(pageId + "|" + layer + "|" + actionType + "|" + semanticName);
                jdbc.update("""
                        INSERT INTO page_actions (
                            action_id, app_id, canonical_page_id, action_fingerprint, action_layer,
                            action_type, semantic_name, description, target, parameters,
                            expected_effect, effect_status, confidence, raw_payload
                        ) VALUES (
                            :id, :appId, :pageId, :fingerprint, :layer,
                            :actionType, :name, :description, :target::jsonb, :parameters::jsonb,
                            :effect::jsonb, 'reviewed', :confidence, :raw::jsonb
                        )
                        """, new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID()).addValue("appId", appId).addValue("pageId", pageId)
                        .addValue("fingerprint", fingerprint).addValue("layer", layer)
                        .addValue("actionType", actionType).addValue("name", semanticName)
                        .addValue("description", value(item.get("description")))
                        .addValue("target", json.write(json.object(item.get("target"))))
                        .addValue("parameters", json.write(json.object(item.get("parameters"))))
                        .addValue("effect", json.write(json.object(item.get("expectedEffect"))))
                        .addValue("confidence", item.get("confidence"))
                        .addValue("raw", json.write(item)));
            }
        }
    }

    private String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            String result = value(source.get(key));
            if (!result.isBlank()) {
                return result;
            }
        }
        return "";
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
