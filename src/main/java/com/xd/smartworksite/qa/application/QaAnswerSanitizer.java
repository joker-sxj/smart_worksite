package com.xd.smartworksite.qa.application;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class QaAnswerSanitizer {
    private static final Pattern CLOSED_THINK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern LEAKED_PREFIX = Pattern.compile("(?is)^.*?</think>");

    public String sanitize(String answer) {
        if (answer == null) {
            return null;
        }
        String sanitized = CLOSED_THINK.matcher(answer).replaceAll("").trim();
        if (sanitized.contains("</think>")) {
            sanitized = LEAKED_PREFIX.matcher(sanitized).replaceFirst("").trim();
        }
        if (sanitized.matches("(?is)^<think>.*")) {
            return "";
        }
        return sanitized;
    }
}
