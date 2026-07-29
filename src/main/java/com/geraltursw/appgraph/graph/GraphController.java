package com.geraltursw.appgraph.graph;

import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/appGraph")
public class GraphController {
    private final GraphService service;

    public GraphController(GraphService service) {
        this.service = service;
    }

    @GetMapping("/appList")
    public Map<String, Object> appList() {
        return service.appList();
    }

    @GetMapping("/queryAppGraph/{appName}")
    public Map<String, Object> queryGraph(@PathVariable String appName) {
        return service.queryGraph(appName);
    }

    @PostMapping("/createOrphanNode")
    public Map<String, Object> createOrphan(@Valid @RequestBody GraphRequests.CreateOrphanNode request) {
        return service.createOrphan(request);
    }

    @PostMapping("/moveNode")
    public Map<String, Object> moveNode(@Valid @RequestBody GraphRequests.MoveNode request) {
        return service.moveNode(request);
    }

    @PostMapping("/deleteNode")
    public Map<String, Object> deleteNode(@Valid @RequestBody GraphRequests.DeleteNode request) {
        return service.deleteNode(request);
    }

    @PostMapping(path = "/updateNode", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> updateNode(
            @RequestParam String pageId,
            @RequestParam String pageTitle,
            @RequestParam String pageText,
            @RequestParam String pageUrl,
            @RequestParam(defaultValue = "") String widgetDescription,
            @RequestParam(defaultValue = "[]") String keepImages,
            @RequestParam(defaultValue = "{}") String aiInference,
            @RequestParam(defaultValue = "{}") String action,
            @RequestParam(defaultValue = "false") boolean aiRecursive,
            @RequestPart(required = false) List<MultipartFile> newImages
    ) {
        return service.updateNode(pageId, pageTitle, pageText, pageUrl, widgetDescription,
                keepImages, aiInference, action, aiRecursive, newImages);
    }

    @GetMapping("/image/{imageName:.+}")
    public ResponseEntity<Resource> image(@PathVariable String imageName) {
        return service.image(imageName);
    }

    @GetMapping("/s3file/image")
    public ResponseEntity<Resource> s3Image(@RequestParam String fileName) {
        return service.image(fileName);
    }
}
