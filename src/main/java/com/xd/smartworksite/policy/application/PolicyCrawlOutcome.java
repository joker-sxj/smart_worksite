package com.xd.smartworksite.policy.application;

/** Centralizes terminal crawl status semantics for mixed fetch/index outcomes. */
public final class PolicyCrawlOutcome {
    private PolicyCrawlOutcome() {}

    public static String status(int indexedCount, int failedCount) {
        if (failedCount <= 0) return "SUCCESS";
        return indexedCount > 0 ? "PARTIAL_SUCCESS" : "FAILED";
    }
}
