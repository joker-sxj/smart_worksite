package com.xd.smartworksite.policy.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyCrawlOutcomeTest {
    @Test
    void reportsPartialSuccessWhenAtLeastOneArticleWasIndexedAndAnotherFailed() {
        assertEquals("PARTIAL_SUCCESS", PolicyCrawlOutcome.status(3, 1));
    }

    @Test
    void reportsFailureWhenNothingWasIndexedAndAnyWorkFailed() {
        assertEquals("FAILED", PolicyCrawlOutcome.status(0, 1));
    }

    @Test
    void reportsSuccessWhenNothingFailed() {
        assertEquals("SUCCESS", PolicyCrawlOutcome.status(3, 0));
    }
}
