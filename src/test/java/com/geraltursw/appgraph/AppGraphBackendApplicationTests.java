package com.geraltursw.appgraph;

import com.geraltursw.appgraph.common.HealthController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppGraphBackendApplicationTests {

	@Test
	void healthContractStaysStable() {
		assertEquals("ok", new HealthController().health().get("status"));
	}

}
