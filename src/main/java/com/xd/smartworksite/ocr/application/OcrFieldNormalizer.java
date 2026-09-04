package com.xd.smartworksite.ocr.application;

import java.util.LinkedHashMap;
import java.util.Map;

/** Applies one field contract before results are persisted or displayed. */
public class OcrFieldNormalizer {
    private static final double MANUAL_CONFIRMATION_THRESHOLD = 0.5;

    public Map<String, Object> normalize(Map<String, Object> source, boolean maskSensitive) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        String key = stringValue(source.get("fieldKey"));
        String rawValue = stringValue(source.get("fieldValue"));
        double confidence = confidenceValue(source.get("confidence"));
        boolean recognized = !rawValue.isBlank();

        result.put("fieldValue", maskSensitive && isSensitive(key)
                ? maskValue(key, rawValue) : rawValue);
        result.put("confidence", confidence);
        result.put("recognized", recognized);
        result.put("manualConfirmationRequired", !recognized
                || confidence < MANUAL_CONFIRMATION_THRESHOLD);
        if (maskSensitive && isSensitive(key) && recognized) {
            result.put("rawFieldValue", rawValue);
        }
        return result;
    }

    private boolean isSensitive(String fieldKey) {
        String key = fieldKey.replaceAll("\\s+", "").toLowerCase();
        return key.equals("idnumber") || key.equals("address") || key.equals("phone");
    }

    private String maskValue(String fieldKey, String value) {
        String key = fieldKey.replaceAll("\\s+", "").toLowerCase();
        if (key.equals("idnumber") && value.length() > 10) {
            return value.substring(0, 6) + "********" + value.substring(value.length() - 4);
        }
        if (key.equals("phone") && value.length() >= 7) {
            return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
        }
        if (value.length() > 4) {
            return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
        }
        return "****";
    }

    private double confidenceValue(Object value) {
        try {
            double parsed = Double.parseDouble(String.valueOf(value));
            return Math.max(0.0, Math.min(1.0, parsed));
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
