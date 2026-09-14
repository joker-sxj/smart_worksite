package com.xd.smartworksite.policy.dto;

public class PolicyPreflightResponse {
    private String status;
    private String reason;
    private String message;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
