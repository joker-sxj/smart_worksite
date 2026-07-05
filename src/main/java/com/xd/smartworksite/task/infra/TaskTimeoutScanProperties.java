package com.xd.smartworksite.task.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.task.timeout-scan")
public class TaskTimeoutScanProperties {

    private boolean enabled;
    private Long fixedDelayMs;
    private Long staleAfterMs;
    private Integer batchLimit;
    private String reason;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(Long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs;
    }

    public Long getStaleAfterMs() {
        return staleAfterMs;
    }

    public void setStaleAfterMs(Long staleAfterMs) {
        this.staleAfterMs = staleAfterMs;
    }

    public Integer getBatchLimit() {
        return batchLimit;
    }

    public void setBatchLimit(Integer batchLimit) {
        this.batchLimit = batchLimit;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
