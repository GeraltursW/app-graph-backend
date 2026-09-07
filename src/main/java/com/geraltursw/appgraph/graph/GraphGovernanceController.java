package com.geraltursw.appgraph.graph;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/appGraph/orphans")
public class GraphGovernanceController {
    private final GraphGovernanceService service;
    public GraphGovernanceController(GraphGovernanceService service){this.service=service;}
    @GetMapping("/workbench") public Object workbench(@RequestParam String appName){return service.workbench(appName);}
    @PostMapping("/batchMerge") public Object merge(@RequestBody Map<String,Object> body){return service.merge(body);}
    @PostMapping("/rollbackBatch") public Object rollback(@RequestBody Map<String,Object> body){return service.rollback(body);}
    @PostMapping("/createProvisionalEntry") public Object pending(@RequestBody Map<String,Object> body){return service.pending(body);}
    @PostMapping("/registerEntry") public Object entry(@RequestBody Map<String,Object> body){return service.registerEntry(body);}
}
