package com.geraltursw.appgraph.functiontree;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FunctionMatchRequestTests {
    @Test
    void matchingDefaultsProtectCandidateVolume() {
        var request = new FunctionTreeController.MatchRequest(
                UUID.randomUUID(), null, null, null, null, null, null, null, null
        );

        assertEquals(5, request.resolvedTopK());
        assertEquals(0.45, request.resolvedMinScore());
        assertEquals(0.85, request.resolvedAutoConfirmScore());
    }
}
