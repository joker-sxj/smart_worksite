package com.xd.smartworksite.ai.dto;

import com.xd.smartworksite.file.domain.DocumentLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EvidenceItem {

    public enum SourceType {
        KNOWLEDGE_DOCUMENT,
        DATABASE
    }

    private final SourceType sourceType;
    private final Long projectId;
    private final Long knowledgeBaseId;
    private final Long documentId;
    private final String chunkId;
    private final Long dataSourceId;
    private final String tableName;
    private final List<String> columnNames;
    private final DocumentLocation location;
    private final String excerpt;
    private final String readOnlySql;
    private final Map<String, Object> metadata;

    private EvidenceItem(SourceType sourceType, Long projectId, Long knowledgeBaseId, Long documentId,
                         String chunkId, Long dataSourceId, String tableName, List<String> columnNames,
                         DocumentLocation location, String excerpt, String readOnlySql,
                         Map<String, Object> metadata) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        this.sourceType = sourceType;
        this.projectId = projectId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.documentId = documentId;
        this.chunkId = blankToNull(chunkId);
        this.dataSourceId = dataSourceId;
        this.tableName = blankToNull(tableName);
        this.columnNames = columnNames == null ? List.of() : List.copyOf(columnNames);
        this.location = location;
        this.excerpt = excerpt == null ? "" : excerpt;
        this.readOnlySql = blankToNull(readOnlySql);
        this.metadata = immutableMap(metadata);
    }

    public static EvidenceItem knowledgeDocument(Long projectId, Long knowledgeBaseId, Long documentId,
                                                 String chunkId, DocumentLocation location, String excerpt) {
        if (knowledgeBaseId == null || documentId == null) {
            throw new IllegalArgumentException("knowledgeBaseId and documentId must not be null");
        }
        return new EvidenceItem(SourceType.KNOWLEDGE_DOCUMENT, projectId, knowledgeBaseId, documentId,
                chunkId, null, null, List.of(), location, excerpt, null, Map.of());
    }

    public static EvidenceItem database(Long projectId, Long dataSourceId, String tableName,
                                        List<String> columnNames, String readOnlySql, String excerpt,
                                        Map<String, Object> metadata) {
        if (dataSourceId == null || tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("dataSourceId and tableName must not be empty");
        }
        return new EvidenceItem(SourceType.DATABASE, projectId, null, null, null, dataSourceId,
                tableName, columnNames, null, excerpt, readOnlySql, metadata);
    }

    public SourceType getSourceType() { return sourceType; }
    public Long getProjectId() { return projectId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public Long getDocumentId() { return documentId; }
    public String getChunkId() { return chunkId; }
    public Long getDataSourceId() { return dataSourceId; }
    public String getTableName() { return tableName; }
    public List<String> getColumnNames() { return columnNames; }
    public DocumentLocation getLocation() { return location; }
    public String getExcerpt() { return excerpt; }
    public String getReadOnlySql() { return readOnlySql; }
    public Map<String, Object> getMetadata() { return metadata; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
