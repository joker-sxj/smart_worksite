package com.xd.smartworksite.policy.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyPreflightContractTest {
    @Test
    void exposesManagedProjectScopedPreflight() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/xd/smartworksite/policy/controller/PolicyController.java"));
        assertThat(source).contains("@PostMapping(\"/sources/preflight\")")
                .contains("hasAuthority('policy:manage')")
                .contains("policyApplicationService.preflightSource(request)");
    }

    @Test
    void crawlSubmissionRejectsRestrictedAndUnknownSourcesIncludingFullProjectRuns() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/xd/smartworksite/policy/application/PolicyApplicationService.java"));
        assertThat(source).contains("validateSourcesForCrawl(sources)")
                .contains("RESTRICTED")
                .contains("UNKNOWN")
                .contains("policy source robots status is unknown");
    }
}
