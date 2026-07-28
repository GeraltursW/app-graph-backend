package com.geraltursw.appgraph.graph;

import jakarta.validation.constraints.NotBlank;

final class GraphRequests {
    private GraphRequests() {
    }

    record CreateOrphanNode(@NotBlank String appName, @NotBlank String pageUrl) {
    }

    record MoveNode(@NotBlank String pageId, @NotBlank String newParentId) {
    }

    record DeleteNode(@NotBlank String id) {
    }
}

