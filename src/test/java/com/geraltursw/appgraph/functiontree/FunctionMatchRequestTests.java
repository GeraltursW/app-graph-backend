package com.geraltursw.appgraph.functiontree;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FunctionMatchRequestTests {
    @Test
    void matchingDefaultsProtectCandidateVolume() {
        var request = new FunctionTreeController.MatchRequest(
                UUID.randomUUID(), null, null, null, null, null, null,
                null, null, null, null, null, null, null
        );

        assertEquals(5, request.resolvedTopK());
        assertEquals(0.45, request.resolvedMinScore());
        assertEquals(0.85, request.resolvedAutoConfirmScore());
        assertEquals(0.55, request.resolvedReviewScore());
        assertEquals(0.90, request.resolvedActionAutoConfirmScore());
        assertEquals(0.65, request.resolvedActionReviewScore());
        assertEquals(0.12, request.resolvedMinScoreMargin());
    }
}
