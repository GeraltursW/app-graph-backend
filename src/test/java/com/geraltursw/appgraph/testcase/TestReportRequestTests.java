package com.geraltursw.appgraph.testcase;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestReportRequestTests {
    @Test
    void importRequestUsesStableDefaultsForScriptCallback() {
        var request = new TestReportController.ImportRunRequest(
                UUID.randomUUID(), null, "ALERT-1", "mqq://message", Map.of(), Map.of(),
                "completed", 88, "passed", "stable", Map.of(), Instant.now(), Instant.now(),
                null, null
        );

        assertEquals("manual", request.resolvedTriggerSource());
        assertEquals(List.of(), request.resolvedSteps());
        assertEquals(List.of(), request.resolvedMetrics());
    }
}
