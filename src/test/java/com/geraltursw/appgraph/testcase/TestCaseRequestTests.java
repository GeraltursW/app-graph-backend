package com.geraltursw.appgraph.testcase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCaseRequestTests {
    @Test
    void generationDefaultsLimitCombinationGrowth() {
        var request = new TestCaseController.GenerateRequest(
                "QQ", null, null, null, null, null, null, null
        );

        assertEquals(2, request.resolvedModes().size());
        assertEquals(4, request.resolvedActionLayers().size());
        assertEquals(2, request.resolvedScenarioDepth());
        assertEquals(12, request.resolvedMaxScenariosPerPage());
    }
}
