package com.geraltursw.appgraph.graph;

import com.geraltursw.appgraph.report.CoverageReportService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="RUN_GOVERNANCE_DB_TESTS",matches="true")
class GovernanceIntegrationTests {
    @Autowired NamedParameterJdbcTemplate db;
    @Autowired GraphGovernanceService graph;
    @Autowired CoverageReportService reports;
    @Autowired GraphService graphQuery;
    UUID app,scan;String name;
    @BeforeEach void setup() {
        app=UUID.randomUUID();scan=UUID.randomUUID();name="verification-"+app;
        db.update("INSERT INTO apps(app_id,package_name,app_name) VALUES(:id,:name,:name)",Map.of("id",app,"name",name));
        db.update("INSERT INTO scans(scan_id,app_id) VALUES(:id,:app)",Map.of("id",scan,"app",app));
    }
    UUID page(String id,String url,String embedding,boolean root) {
        UUID page=UUID.randomUUID();
        db.update("INSERT INTO canonical_pages(canonical_page_id,app_id,canonical_page_key,page_hash_id,display_name,page_type) VALUES(:id,:app,:hash,:hash,:hash,'screen')",Map.of("id",page,"app",app,"hash",id));
        db.update("INSERT INTO page_instances(page_instance_id,scan_id,app_id,canonical_page_id,page_title,page_type,page_url,embedding_text) VALUES(:id,:scan,:app,:page,:title,'screen',:url,:embedding)",Map.of("id",UUID.randomUUID(),"scan",scan,"app",app,"page",page,"title",id,"url",url,"embedding",embedding));
        if(root)db.update("INSERT INTO graph_entry_points(app_id,page_id,entry_kind,evidence) VALUES(:app,:id,'APP_HOME','test evidence')",Map.of("app",app,"id",page));return page;
    }
    void edge(UUID from,UUID to) {
        db.update("INSERT INTO page_edges(edge_id,scan_id,app_id,from_canonical_page_id,to_canonical_page_id,label) VALUES(:id,:scan,:app,:from,:to,'tap')",Map.of("id",UUID.randomUUID(),"scan",scan,"app",app,"from",from,"to",to));
    }
    Map<String,Object> batch(List<?> items) {return Map.of("requestId",UUID.randomUUID().toString(),"appName",name,"graphVersion",graph.version(app),"items",items);}
    @Test void coverageIsUrlScopedAndIndependentOfImageReassignment() {
        String prefix=app.toString();UUID first=page(prefix+"a","url-U","classification",true);
        Object time=db.queryForObject("SELECT first_coverage_time FROM app_url_coverage_fact WHERE app_id=:id",Map.of("id",app),Object.class);
        page(prefix+"b","url-U","other screenshot classification",false);
        db.update("UPDATE page_instances SET embedding_text='updated classification',images='[\"new-image.png\"]'::jsonb WHERE canonical_page_id=:id",Map.of("id",first));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM app_url_coverage_fact WHERE app_id=:id",Map.of("id",app),Integer.class));
        assertEquals(time,db.queryForObject("SELECT first_coverage_time FROM app_url_coverage_fact WHERE app_id=:id",Map.of("id",app),Object.class));
        db.update("UPDATE page_instances SET page_url='url-new' WHERE canonical_page_id=:id",Map.of("id",first));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM app_url_coverage_fact WHERE app_id=:id",Map.of("id",app),Integer.class));
        page(prefix+"c","placeholder","",false);
        assertEquals(2,db.queryForObject("SELECT count(*) FROM app_url_coverage_fact WHERE app_id=:id",Map.of("id",app),Integer.class));
    }
    @Test void allNewEdgesRejectCyclesButAllowMultipleParents() {
        String p=app.toString();UUID a=page(p+"a","a","",true),b=page(p+"b","b","",false),c=page(p+"c","c","",false),d=page(p+"d","d","",false);
        edge(a,b);edge(a,c);edge(b,d);edge(c,d);
        assertThrows(Exception.class,()->edge(d,a));assertThrows(Exception.class,()->edge(a,a));
        assertEquals(4,graph.reachable(app).size());
        var payload=graphQuery.queryGraph(name);
        assertEquals(4,((List<?>)payload.get("edges")).size());
    }
    @Test void concurrentReverseEdgesCannotCreateCycle() throws Exception {
        UUID a=page(app+"a","a","",true), b=page(app+"b","b","",false);
        var source=Objects.requireNonNull(db.getJdbcTemplate().getDataSource());
        String sql="INSERT INTO page_edges(edge_id,scan_id,app_id,from_canonical_page_id,to_canonical_page_id,label) VALUES(?,?,?,?,?,'tap')";
        try(var first=source.getConnection(); var executor=Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            try(var insert=first.prepareStatement(sql)) {
                insert.setObject(1,UUID.randomUUID()); insert.setObject(2,scan); insert.setObject(3,app);
                insert.setObject(4,a); insert.setObject(5,b); insert.executeUpdate();
            }
            var ready=new CountDownLatch(1);
            var reverse=executor.submit(()->{
                try(var second=source.getConnection();var insert=second.prepareStatement(sql)) {
                    insert.setQueryTimeout(10);
                    insert.setObject(1,UUID.randomUUID()); insert.setObject(2,scan); insert.setObject(3,app);
                    insert.setObject(4,b); insert.setObject(5,a); ready.countDown();
                    insert.executeUpdate(); return "accepted";
                } catch(SQLException e) {return e.getSQLState();}
            });
            assertTrue(ready.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->reverse.get(200,TimeUnit.MILLISECONDS));
            first.commit();
            assertEquals("23514",reverse.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,db.queryForObject("SELECT count(*) FROM page_edges WHERE app_id=:id",Map.of("id",app),Integer.class));
    }
    @Test void batchIsAtomicIdempotentAndUndoable() {
        String p=app.toString();page(p+"a","a","",true);page(p+"b","b","",false);page(p+"c","c","",false);
        var one=Map.of("pageId",p+"b","newParentId",p+"a","widgetDescription","message");
        var invalid=Map.of("pageId",p+"c","newParentId",p+"a","widgetDescription","");
        assertThrows(IllegalArgumentException.class,()->graph.merge(batch(List.of(one,invalid))));
        assertEquals(1,graph.reachable(app).size());
        var request=batch(List.of(one));var result=graph.merge(request);assertEquals(result.get("batchId"),graph.merge(request).get("batchId"));assertEquals(2,graph.reachable(app).size());
        graph.rollback(Map.of("appName",name,"batchId",result.get("batchId"),"graphVersion",graph.version(app)));
        assertEquals(1,graph.reachable(app).size());
    }
    @Test void provisionalAreaDoesNotInventRootEdge() {
        String id=app+"area";
        UUID entry=page(id,"new-area","classified",false);
        UUID detail=page(app+"detail","detail","classified",false);
        edge(entry,detail);
        graph.pending(Map.of("appName",name,"pageId",id,"graphVersion",graph.version(app),"reason","No verified entry yet"));
        assertTrue(graph.reachable(app).isEmpty());
        assertEquals(1,((List<?>)graphQuery.queryGraph(name).get("orphanPages")).size());
        graph.registerEntry(Map.of("appName",name,"pageId",id,"graphVersion",graph.version(app),"entryKind","DEEPLINK","evidence","Verified test entry"));
        assertEquals(Set.of(entry,detail),graph.reachable(app));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM page_edges WHERE app_id=:id",Map.of("id",app),Integer.class));
        assertTrue(((List<?>)graphQuery.queryGraph(name).get("orphanPages")).isEmpty());
    }
    @Test void reportUsesBackendBaselineAndImmutableSnapshot() {
        page(app+"a","u","classified",true);page(app+"b","u","classified",false);page(app+"c","gap","",false);
        var config=Map.of("appName",name,"appGroups",List.of("TOP"),"totalUrlCount",10,"priorityUrls",List.of("u","missing"),"specialNote","");
        reports.publish(Map.of("requestId",UUID.randomUUID().toString(),"expectedRevision",reports.baseline().get("revision"),"source","integration-test","apps",List.of(config)));
        var request=Map.<String,Object>of("requestId",UUID.randomUUID().toString(),"date",LocalDate.now().minusDays(1).toString(),"reportType","EVENING");
        var report=reports.generate(request);assertEquals(report.get("reportId"),reports.generate(request).get("reportId"));
        @SuppressWarnings("unchecked") var metrics=(Map<String,Object>)((List<?>)report.get("apps")).getFirst();
        assertEquals(2,metrics.get("nodeCount"));assertEquals(1L,metrics.get("coveredUrlCount"));assertEquals(1L,metrics.get("orphanUrlCount"));assertEquals(1L,metrics.get("priorityCoveredCount"));
        db.update("UPDATE page_instances SET embedding_text='' WHERE app_id=:id",Map.of("id",app));
        assertEquals(report.get("reportId"),reports.get(report.get("reportId").toString()).get("reportId"));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM app_url_coverage_fact WHERE app_id=:id",Map.of("id",app),Integer.class));
    }
}
