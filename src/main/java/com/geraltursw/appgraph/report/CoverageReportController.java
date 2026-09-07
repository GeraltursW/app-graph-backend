package com.geraltursw.appgraph.report;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/appGraph/reports")
public class CoverageReportController {
    private final CoverageReportService service;
    public CoverageReportController(CoverageReportService service){this.service=service;}
    @GetMapping("/baselines/current") public Map<String,Object> baseline(){return service.baseline();}
    @PostMapping("/baselines/publish") public Map<String,Object> publish(@RequestBody Map<String,Object> body){return service.publish(body);}
    @GetMapping("/daily") public Object list(){return service.list();}
    @GetMapping("/trends") public Object trends(){return service.list();}
    @GetMapping("/{id}") public Object get(@PathVariable String id){return service.get(id);}
    @PostMapping("/generate") public Object generate(@RequestBody Map<String,Object> body){return service.generate(body);}
}
