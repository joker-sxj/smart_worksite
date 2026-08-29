package com.xd.smartworksite.file.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.file")
public class FileProperties {

    private long accessUrlExpireSeconds = 600;
    private long maxSizeBytes = 104857600;
    private Parse parse = new Parse();
    private List<String> allowedContentTypes = new ArrayList<>(List.of(
            "application/pdf",
            "text/plain",
            "image/png",
            "image/jpeg",
            "image/webp",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "application/csv",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    ));

    public long getAccessUrlExpireSeconds() {
        return accessUrlExpireSeconds;
    }

    public void setAccessUrlExpireSeconds(long accessUrlExpireSeconds) {
        this.accessUrlExpireSeconds = accessUrlExpireSeconds;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    public List<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public Parse getParse() {
        return parse;
    }

    public void setParse(Parse parse) {
        this.parse = parse;
    }

    public static class Parse {

        private boolean enabled = true;
        private int maxPages = 100;
        private int resultPreviewLength = 2000;
        private int maxInputChars = 120000;
        private int maxSpreadsheetRows = 10000;
        private int maxSpreadsheetCells = 200000;
        private int maxSpreadsheetColumnSpan = 16384;
        private int maxSpreadsheetExpandedCells = 2_000_000;
        private int maxSlides = 200;
        private int maxPresentationShapes = 10000;
        private int maxPresentationCells = 100000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }

        public int getResultPreviewLength() {
            return resultPreviewLength;
        }

        public void setResultPreviewLength(int resultPreviewLength) {
            this.resultPreviewLength = resultPreviewLength;
        }

        public int getMaxInputChars() {
            return maxInputChars;
        }

        public void setMaxInputChars(int maxInputChars) {
            this.maxInputChars = maxInputChars;
        }


        public int getMaxSpreadsheetRows() {
            return maxSpreadsheetRows;
        }

        public void setMaxSpreadsheetRows(int maxSpreadsheetRows) {
            this.maxSpreadsheetRows = maxSpreadsheetRows;
        }

        public int getMaxSpreadsheetCells() {
            return maxSpreadsheetCells;
        }

        public void setMaxSpreadsheetCells(int maxSpreadsheetCells) {
            this.maxSpreadsheetCells = maxSpreadsheetCells;
        }

        public int getMaxSpreadsheetColumnSpan() {
            return maxSpreadsheetColumnSpan;
        }

        public void setMaxSpreadsheetColumnSpan(int maxSpreadsheetColumnSpan) {
            this.maxSpreadsheetColumnSpan = maxSpreadsheetColumnSpan;
        }

        public int getMaxSpreadsheetExpandedCells() {
            return maxSpreadsheetExpandedCells;
        }

        public void setMaxSpreadsheetExpandedCells(int maxSpreadsheetExpandedCells) {
            this.maxSpreadsheetExpandedCells = maxSpreadsheetExpandedCells;
        }
        public int getMaxSlides() {
            return maxSlides;
        }

        public void setMaxSlides(int maxSlides) {
            this.maxSlides = maxSlides;
        }
        public int getMaxPresentationShapes() {
            return maxPresentationShapes;
        }

        public void setMaxPresentationShapes(int maxPresentationShapes) {
            this.maxPresentationShapes = maxPresentationShapes;
        }

        public int getMaxPresentationCells() {
            return maxPresentationCells;
        }

        public void setMaxPresentationCells(int maxPresentationCells) {
            this.maxPresentationCells = maxPresentationCells;
        }

    }

}
