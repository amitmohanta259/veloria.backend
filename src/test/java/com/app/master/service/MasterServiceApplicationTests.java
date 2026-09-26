package com.app.master.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// The AWS credentials are environment lookups with no default, so a missing
// variable fails the real application by name. The test context has no
// environment to read from and no S3 calls to make, so it supplies obvious
// non-credentials here rather than requiring real ones on every machine.
@SpringBootTest(properties = {
        "AWS_ACCESS_KEY=test-placeholder-not-a-credential",
        "AWS_SECRET_KEY=test-placeholder-not-a-credential",
        // One shared pool size across every integration test, on purpose.
        //
        // Spring caches a context per distinct property set, and each context
        // brings its own connection pool. Five different sizes meant five pools
        // — 160 connections against a server that allows 100, so a suite run
        // died with "too many clients" while each test passed alone. Identical
        // properties mean the contexts are shared, and 40 is enough for the
        // largest concurrency test now that placing an order also books its sale.
        "spring.datasource.hikari.maximum-pool-size=40"
})
class MasterServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
