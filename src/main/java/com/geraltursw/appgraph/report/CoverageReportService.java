package com.geraltursw.appgraph.report;

import com.geraltursw.appgraph.common.ConflictException;
import com.geraltursw.appgraph.common.JsonSupport;
import com.geraltursw.appgraph.common.NotFoundException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class CoverageReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonSupport json;
    public CoverageReportService(NamedParameterJdbcTemplate jdbc, JsonSupport json) { this.jdbc=jdbc; this.json=json; }

    public Map<String,Object> baseline() {
        var rows=jdbc.queryForList("SELECT revision,source,payload,published_at FROM report_baselines ORDER BY revision DESC LIMIT 1",Map.of());
        if(rows.isEmpty()) return Map.of("revision",0,"source","","apps",List.of());
        var row=rows.getFirst();
        var result=new LinkedHashMap<>(json.object(row.get("payload")));
        result.put("revision",row.get("revision")); result.put("source",row.get("source")); result.put("publishedAt",row.get("published_at").toString());
        return result;
    }

    @Transactional
    public Map<String,Object> publish(Map<String,Object> body) {
        UUID requestId=UUID.fromString(required(body,"requestId"));
        jdbc.queryForList("SELECT pg_advisory_xact_lock(6100701)",Map.of());
        var previous=jdbc.queryForList("SELECT revision,payload,source FROM report_baselines WHERE request_id=:id",Map.of("id",requestId));
        String source=required(body,"source");
        var allowed=new HashSet<>(jdbc.queryForList("SELECT app_name FROM apps",Map.of(),String.class));
        var names=new HashSet<String>();
        var apps=new ArrayList<Map<String,Object>>();
        for(Object item: json.array(body.get("apps"))) {
            var app=json.object(item); String name=required(app,"appName");
            if(!allowed.contains(name)||!names.add(name)) throw new IllegalArgumentException("Unknown or duplicate app: "+name);
            Object total=app.get("totalUrlCount");
            if(total!=null && (!(total instanceof Number n)||n.doubleValue()!=n.longValue()||n.longValue()<0||n.longValue()>Integer.MAX_VALUE)) throw new IllegalArgumentException("Invalid totalUrlCount");
            var groups=json.array(app.get("appGroups")).stream().map(Object::toString).distinct().toList();
            if(groups.stream().anyMatch(g->!Set.of("TOP","TGI").contains(g))) throw new IllegalArgumentException("Invalid app group");
            var urls=new LinkedHashSet<String>();
            for(Object url:json.array(app.get("priorityUrls"))) { if(!(url instanceof String s)||s.isBlank()) throw new IllegalArgumentException("Empty priority URL"); urls.add(s); }
            var entry=new LinkedHashMap<String,Object>(); entry.put("appName",name); entry.put("appGroups",groups);
            entry.put("totalUrlCount",total); entry.put("priorityUrls",urls); entry.put("specialNote",String.valueOf(app.getOrDefault("specialNote",""))); apps.add(entry);
        }
        var payload=Map.of("apps",apps);
        if(!previous.isEmpty()) {
            if(!json.object(previous.getFirst().get("payload")).equals(json.object(json.write(payload)))||!source.equals(previous.getFirst().get("source"))) throw new ConflictException("requestId reused with different content");
            return Map.of("revision",previous.getFirst().get("revision"));
        }
        long current=((Number)baseline().get("revision")).longValue();
        if(!(body.get("expectedRevision") instanceof Number n)||n.longValue()!=current) throw new ConflictException("Baseline changed; reload before publishing");
        Long revision=jdbc.queryForObject("INSERT INTO report_baselines(request_id,source,payload) VALUES(:id,:source,:payload::jsonb) RETURNING revision",Map.of("id",requestId,"source",source,"payload",json.write(payload)),Long.class);
        return Map.of("revision",revision);
    }

    public static Instant[] period(LocalDate date,String type) {
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        if("MORNING".equals(type)) return new Instant[]{date.minusDays(1).atTime(20,0).atZone(zone).toInstant(),date.atTime(8,0).atZone(zone).toInstant()};
        if("EVENING".equals(type)) return new Instant[]{date.atTime(8,0).atZone(zone).toInstant(),date.atTime(20,0).atZone(zone).toInstant()};
        throw new IllegalArgumentException("reportType must be MORNING or EVENING");
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> generate(Map<String,Object> body) {
        UUID requestId=UUID.fromString(required(body,"requestId"));
        var previous=jdbc.queryForList("SELECT request_body,payload FROM graph_daily_reports WHERE request_id=:id",Map.of("id",requestId));
        if(!previous.isEmpty()) {
            if(!json.object(previous.getFirst().get("request_body")).equals(body)) throw new ConflictException("requestId reused with different content");
            return json.object(previous.getFirst().get("payload"));
        }
        var baseline=baseline();
        if(((Number)baseline.get("revision")).longValue()==0) throw new ConflictException("Publish a report baseline first");
        String type=required(body,"reportType"); LocalDate date=LocalDate.parse(required(body,"date"));
        var window=period(date,type);
        if(window[1].isAfter(Instant.now())) throw new IllegalArgumentException("The reporting period has not ended");
        var report=buildReport(baseline,date,type,window);
        UUID id=UUID.randomUUID(); report.put("reportId",id.toString());
        jdbc.update("INSERT INTO graph_daily_reports(report_id,request_id,request_body,report_type,report_date,baseline_revision,payload) VALUES(:id,:request,:body::jsonb,:type,:date,:revision,:payload::jsonb)",Map.of("id",id,"request",requestId,"body",json.write(body),"type",type,"date",date,"revision",baseline.get("revision"),"payload",json.write(report)));
        return report;
    }

    private Map<String,Object> buildReport(Map<String,Object> baseline,LocalDate date,String type,Instant[] window) {
        var source=jdbc.queryForList("""
            SELECT a.app_name,p.page_hash_id,p.display_name,i.page_url,i.embedding_text,
                   f.first_coverage_time,f.is_baseline
            FROM apps a JOIN canonical_pages p ON p.app_id=a.app_id
            JOIN page_instances i ON i.canonical_page_id=p.canonical_page_id
            LEFT JOIN app_url_coverage_fact f ON f.app_id=a.app_id AND f.page_url=i.page_url
              AND f.page_url_hash=encode(sha256(convert_to(i.page_url,'UTF8')),'hex')
            WHERE nullif(btrim(i.page_url),'') IS NOT NULL
            """,Map.of());
        var facts=jdbc.queryForList("""
            SELECT a.app_name,f.page_url,f.first_coverage_time FROM app_url_coverage_fact f JOIN apps a ON a.app_id=f.app_id
            WHERE NOT f.is_baseline AND f.first_coverage_time>=:start AND f.first_coverage_time<:end
            """,Map.of("start",java.sql.Timestamp.from(window[0]),"end",java.sql.Timestamp.from(window[1])));
        var functionCounts=jdbc.queryForList("""
            SELECT a.app_name,count(n.function_id) AS count FROM apps a
            JOIN LATERAL (SELECT catalog_id FROM function_catalogs WHERE app_id=a.app_id ORDER BY imported_at DESC LIMIT 1) c ON true
            LEFT JOIN function_nodes n ON n.catalog_id=c.catalog_id GROUP BY a.app_name
            """,Map.of());
        var metrics=new ArrayList<Map<String,Object>>();
        for(Object target:json.array(baseline.get("apps"))) {
            var config=json.object(target); String appName=config.get("appName").toString();
            var urls=new LinkedHashMap<String,Map<String,Object>>(); var nodeIds=new HashSet<String>();
            for(var row:source) if(appName.equals(row.get("app_name"))) {
                String url=row.get("page_url").toString(); boolean covered=row.get("embedding_text")!=null&&!row.get("embedding_text").toString().isBlank();
                if(covered) nodeIds.add(row.get("page_hash_id").toString());
                var item=urls.computeIfAbsent(url,k->{var m=new LinkedHashMap<String,Object>();m.put("pageUrl",url);m.put("pageIds",new LinkedHashSet<String>());m.put("covered",false);m.put("isNew",false);m.put("priority",false);return m;});
                @SuppressWarnings("unchecked") var ids=(Set<String>)item.get("pageIds"); ids.add(row.get("page_hash_id").toString());
                item.put("pageTitle",row.get("display_name"));
                item.put("covered",covered||Boolean.TRUE.equals(item.get("covered")));
                item.put("firstCoverageTime",row.get("first_coverage_time")==null?null:row.get("first_coverage_time").toString());
            }
            int newCount=0;
            for(var fact:facts) if(appName.equals(fact.get("app_name"))) {
                String url=fact.get("page_url").toString();newCount++;
                var item=urls.computeIfAbsent(url,k->{var m=new LinkedHashMap<String,Object>();m.put("pageUrl",url);m.put("pageIds",List.of());m.put("covered",false);m.put("priority",false);return m;});
                item.put("isNew",true);item.put("firstCoverageTime",fact.get("first_coverage_time").toString());
            }
            for(Object raw:json.array(config.get("priorityUrls"))) {
                String url=raw.toString();var item=urls.computeIfAbsent(url,k->{var m=new LinkedHashMap<String,Object>();m.put("pageUrl",url);m.put("pageIds",List.of());m.put("covered",false);m.put("isNew",false);return m;});item.put("priority",true);
            }
            long covered=urls.values().stream().filter(u->Boolean.TRUE.equals(u.get("covered"))).count();
            long orphan=urls.values().stream().filter(u->!Boolean.TRUE.equals(u.get("covered"))&&u.get("pageIds") instanceof Set<?> ids&&!ids.isEmpty()).count();
            long priorityCovered=urls.values().stream().filter(u->Boolean.TRUE.equals(u.get("priority"))&&Boolean.TRUE.equals(u.get("covered"))).count();
            var m=new LinkedHashMap<>(config);m.remove("priorityUrls");m.put("nodeCount",nodeIds.size());m.put("coveredUrlCount",covered);m.put("orphanUrlCount",orphan);m.put("newUrlCount",newCount);
            m.put("priorityUrlCount",json.array(config.get("priorityUrls")).size());m.put("priorityCoveredCount",priorityCovered);m.put("urls",urls.values());
            m.put("functionCount",functionCounts.stream().filter(f->appName.equals(f.get("app_name"))).map(f->f.get("count")).findFirst().orElse(null));
            metrics.add(m);
        }
        var result=new LinkedHashMap<String,Object>();result.put("date",date.toString());result.put("reportType",type);result.put("generatedAt",Instant.now().toString());result.put("periodStart",window[0].toString());result.put("periodEnd",window[1].toString());
        result.put("baselineRevision",baseline.get("revision"));result.put("baselineSource",baseline.get("source"));result.put("metricPolicyVersion","url-coverage-v1");result.put("apps",metrics);return result;
    }

    public List<Map<String,Object>> list() {
        return jdbc.query("SELECT payload FROM (SELECT DISTINCT ON (report_date,report_type) payload,generated_at FROM graph_daily_reports ORDER BY report_date,report_type,generated_at DESC) r ORDER BY generated_at DESC LIMIT 60",Map.of(),(rs,n)->json.object(rs.getString(1)));
    }
    public Map<String,Object> get(String id) {
        var rows=jdbc.query("SELECT payload FROM graph_daily_reports WHERE report_id=:id",Map.of("id",UUID.fromString(id)),(rs,n)->json.object(rs.getString(1)));
        if(rows.isEmpty()) throw new NotFoundException("Report not found");return rows.getFirst();
    }
    private static String required(Map<String,Object> b,String key) { if(!(b.get(key) instanceof String s)||s.isBlank()) throw new IllegalArgumentException(key+" is required");return s; }
}
