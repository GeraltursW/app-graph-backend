package com.geraltursw.appgraph.graph;

import com.geraltursw.appgraph.common.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.*;

@Service
public class GraphGovernanceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonSupport json;
    public GraphGovernanceService(NamedParameterJdbcTemplate jdbc,JsonSupport json){this.jdbc=jdbc;this.json=json;}

    UUID app(String name,boolean lock) {
        var rows=jdbc.queryForList("SELECT app_id FROM apps WHERE app_name=:name"+(lock?" FOR UPDATE":""),Map.of("name",name));
        if(rows.size()!=1) throw new IllegalArgumentException("App name must identify exactly one app");return (UUID)rows.getFirst().get("app_id");
    }
    long version(UUID app){return jdbc.queryForObject("SELECT graph_version FROM apps WHERE app_id=:id",Map.of("id",app),Long.class);}
    public Set<UUID> reachable(UUID app) {
        return new HashSet<>(jdbc.queryForList("""
            WITH RECURSIVE reachable(id) AS (
              SELECT page_id FROM graph_entry_points WHERE app_id=:app
              UNION SELECT e.to_canonical_page_id FROM page_edges e JOIN reachable r ON e.from_canonical_page_id=r.id
              WHERE e.app_id=:app AND e.to_canonical_page_id IS NOT NULL
            ) SELECT id FROM reachable
            """,Map.of("app",app),UUID.class));
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> workbench(String name) {
        UUID app=app(name,false);var reached=reachable(app);
        var nodes=jdbc.query("""
            SELECT p.canonical_page_id,p.page_hash_id,p.display_name,i.page_url,i.images,i.embedding_text,i.raw_ai_payload,
                   pending.reason
            FROM canonical_pages p LEFT JOIN LATERAL (
              SELECT * FROM page_instances WHERE canonical_page_id=p.canonical_page_id ORDER BY created_at DESC LIMIT 1
            ) i ON true LEFT JOIN graph_pending_entries pending ON pending.page_id=p.canonical_page_id
            WHERE p.app_id=:app ORDER BY p.created_at
            """,Map.of("app",app),(rs,n)->{
                var m=new LinkedHashMap<String,Object>();m.put("canonicalId",rs.getObject(1).toString());m.put("pageId",rs.getString(2));m.put("pageTitle",rs.getString(3));m.put("pageUrl",Objects.toString(rs.getString(4),""));m.put("images",json.array(rs.getString(5)));m.put("covered",rs.getString(6)!=null&&!rs.getString(6).isBlank());m.put("raw",json.object(rs.getString(7)));m.put("pendingReason",Objects.toString(rs.getString(8),""));m.put("reachable",reached.contains(UUID.fromString(rs.getString(1))));return m;
            });
        var edges=jdbc.queryForList("SELECT from_canonical_page_id,to_canonical_page_id FROM page_edges WHERE app_id=:app",Map.of("app",app));
        var children=new HashMap<String,List<String>>(); var incoming=new HashSet<String>();
        edges.forEach(e->{if(e.get("from_canonical_page_id")!=null&&e.get("to_canonical_page_id")!=null){String from=e.get("from_canonical_page_id").toString(),to=e.get("to_canonical_page_id").toString();children.computeIfAbsent(from,k->new ArrayList<>()).add(to);incoming.add(to);}});
        var entries=new ArrayList<Map<String,Object>>();
        for(var node:nodes) {
            if(Boolean.TRUE.equals(node.get("reachable"))) continue;
            if(incoming.contains(node.get("canonicalId"))) continue;
            var descendants=descendants(node.get("canonicalId").toString(),children);
            var item=new LinkedHashMap<>(node);item.put("memberPageIds",nodes.stream().filter(n->descendants.contains(n.get("canonicalId"))&&!Boolean.TRUE.equals(n.get("reachable"))).map(n->n.get("pageId")).toList());
            var raw=json.object(node.get("raw"));String previous=Objects.toString(raw.getOrDefault("previousPageId",raw.get("previous_page_id")),"");
            var candidates=new ArrayList<Map<String,Object>>();
            if(!previous.isBlank()) for(var candidate:nodes) if(previous.equals(candidate.get("pageId"))&&Boolean.TRUE.equals(candidate.get("reachable"))) candidates.add(Map.of("parentPageId",previous,"parentTitle",candidate.get("pageTitle"),"reason","采集记录提供前序页面，需核对进入控件","autoEligible",false));
            item.put("candidates",candidates);item.remove("raw");entries.add(item);
        }
        nodes.forEach(n->n.remove("raw"));
        long unresolved=nodes.stream().filter(n->!Boolean.TRUE.equals(n.get("reachable"))).count();
        var represented=new HashSet<>();entries.forEach(e->represented.addAll(json.array(e.get("memberPageIds"))));
        return Map.of("graphVersion",version(app),"nodes",nodes,"entries",entries,"unresolvedCount",unresolved,"unrepresentedCount",unresolved-represented.size());
    }
    static Set<String> descendants(String id,Map<String,List<String>> children) {
        var seen=new HashSet<String>();var queue=new ArrayDeque<String>();queue.add(id);
        while(!queue.isEmpty()){var next=queue.remove();if(seen.add(next))queue.addAll(children.getOrDefault(next,List.of()));}return seen;
    }
    UUID page(UUID app,String hash) {
        var rows=jdbc.queryForList("SELECT canonical_page_id FROM canonical_pages WHERE app_id=:app AND page_hash_id=:hash",Map.of("app",app,"hash",hash),UUID.class);
        if(rows.isEmpty())throw new IllegalArgumentException("Page not found in selected app");return rows.getFirst();
    }
    void checkVersion(UUID app,Object expected){if(!(expected instanceof Number n)||n.longValue()!=version(app))throw new ConflictException("Graph changed; reload and review again");}

    @Transactional
    public Map<String,Object> merge(Map<String,Object> body) {
        UUID app=app(required(body,"appName"),true);UUID request=UUID.fromString(required(body,"requestId"));
        var previous=jdbc.queryForList("SELECT request_body,response_body FROM graph_operation_batches WHERE request_id=:id",Map.of("id",request));
        if(!previous.isEmpty()){if(!json.object(previous.getFirst().get("request_body")).equals(json.object(json.write(body))))throw new ConflictException("requestId content mismatch");return json.object(previous.getFirst().get("response_body"));}
        checkVersion(app,body.get("graphVersion"));var items=json.array(body.get("items"));if(items.isEmpty()||items.size()>500)throw new IllegalArgumentException("Select 1 to 500 entries");
        var seen=new HashSet<UUID>();var changes=new ArrayList<Map<String,Object>>();
        for(Object value:items){var item=json.object(value);UUID child=page(app,required(item,"pageId")),parent=page(app,required(item,"newParentId"));String widget=required(item,"widgetDescription");
            if(!seen.add(child))throw new IllegalArgumentException("Duplicate entry in batch");
            if(!reachable(app).contains(parent))throw new ConflictException("Parent is not reachable from a confirmed app entry");
            if(reachable(app).contains(child))throw new ConflictException("Entry already merged; reload");
            var scan=jdbc.queryForList("SELECT scan_id FROM page_instances WHERE canonical_page_id=:id ORDER BY created_at DESC LIMIT 1",Map.of("id",child),UUID.class);
            if(scan.isEmpty())throw new ConflictException("Entry has no captured page instance");
            UUID edge=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO page_edges(edge_id,scan_id,app_id,from_canonical_page_id,to_canonical_page_id,action_type,label,widget_description,status,raw_action_payload)
                VALUES(:id,:scan,:app,:parent,:child,'tap',:widget,:widget,'confirmed',:raw::jsonb)
                """,Map.of("id",edge,"scan",scan.getFirst(),"app",app,"parent",parent,"child",child,"widget",widget,"raw",json.write(Map.of("source","manualBatch","requestId",request.toString()))));
            var pending=jdbc.queryForList("SELECT reason FROM graph_pending_entries WHERE page_id=:id",Map.of("id",child));
            changes.add(Map.of("edgeId",edge.toString(),"pageId",child.toString(),"pendingReason",pending.isEmpty()?"":pending.getFirst().get("reason")));
            jdbc.update("DELETE FROM graph_pending_entries WHERE page_id=:id",Map.of("id",child));
        }
        long version=version(app);var result=Map.<String,Object>of("status","success","batchId",request.toString(),"graphVersion",version,"successCount",items.size());
        jdbc.update("INSERT INTO graph_operation_batches(request_id,app_id,request_body,response_body,changes,graph_version) VALUES(:id,:app,:body::jsonb,:response::jsonb,:changes::jsonb,:version)",Map.of("id",request,"app",app,"body",json.write(body),"response",json.write(result),"changes",json.write(changes),"version",version));return result;
    }
    @Transactional
    public Map<String,Object> rollback(Map<String,Object> body) {
        UUID app=app(required(body,"appName"),true),batch=UUID.fromString(required(body,"batchId"));
        var rows=jdbc.queryForList("SELECT * FROM graph_operation_batches WHERE request_id=:id AND app_id=:app FOR UPDATE",Map.of("id",batch,"app",app));
        if(rows.isEmpty())throw new NotFoundException("Batch not found");var row=rows.getFirst();
        if(Boolean.TRUE.equals(row.get("rolled_back")))return Map.of("status","success","alreadyRolledBack",true);
        checkVersion(app,body.get("graphVersion"));checkVersion(app,row.get("graph_version"));
        for(Object raw:json.array(row.get("changes"))){var change=json.object(raw);jdbc.update("DELETE FROM page_edges WHERE edge_id=:id",Map.of("id",UUID.fromString(change.get("edgeId").toString())));
            if(!change.get("pendingReason").toString().isBlank())jdbc.update("INSERT INTO graph_pending_entries(page_id,reason) VALUES(:id,:reason) ON CONFLICT(page_id) DO UPDATE SET reason=excluded.reason",Map.of("id",UUID.fromString(change.get("pageId").toString()),"reason",change.get("pendingReason")));
        }
        jdbc.update("UPDATE graph_operation_batches SET rolled_back=true WHERE request_id=:id",Map.of("id",batch));return Map.of("status","success","graphVersion",version(app));
    }
    @Transactional
    public Map<String,Object> pending(Map<String,Object> body) {
        UUID app=app(required(body,"appName"),true);checkVersion(app,body.get("graphVersion"));UUID page=page(app,required(body,"pageId"));
        if(reachable(app).contains(page))throw new ConflictException("Page is already reachable");
        jdbc.update("INSERT INTO graph_pending_entries(page_id,reason) VALUES(:id,:reason) ON CONFLICT(page_id) DO UPDATE SET reason=excluded.reason",Map.of("id",page,"reason",required(body,"reason")));
        jdbc.update("UPDATE apps SET graph_version=graph_version+1 WHERE app_id=:id",Map.of("id",app));return Map.of("status","success","graphVersion",version(app));
    }
    @Transactional
    public Map<String,Object> registerEntry(Map<String,Object> body) {
        UUID app=app(required(body,"appName"),true);checkVersion(app,body.get("graphVersion"));UUID page=page(app,required(body,"pageId"));String kind=required(body,"entryKind");
        if(!Set.of("APP_HOME","DEEPLINK","NOTIFICATION","SYSTEM_INTENT","EXTERNAL_APP").contains(kind))throw new IllegalArgumentException("Unsupported entry kind");
        jdbc.update("INSERT INTO graph_entry_points(app_id,page_id,entry_kind,evidence) VALUES(:app,:page,:kind,:evidence) ON CONFLICT(page_id) DO UPDATE SET entry_kind=excluded.entry_kind,evidence=excluded.evidence",Map.of("app",app,"page",page,"kind",kind,"evidence",required(body,"evidence")));
        jdbc.update("DELETE FROM graph_pending_entries WHERE page_id=:id",Map.of("id",page));return Map.of("status","success","graphVersion",version(app));
    }
    static String required(Map<String,Object> body,String key){if(!(body.get(key) instanceof String s)||s.isBlank())throw new IllegalArgumentException(key+" is required");return s;}
}
