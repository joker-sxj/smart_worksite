package com.xd.smartworksite.task.application;

public class NonRetryableTaskException extends RuntimeException {
    public NonRetryableTaskException(String message) {
        super(message);
    }

    public NonRetryableTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
