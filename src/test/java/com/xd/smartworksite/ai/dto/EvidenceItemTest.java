package com.xd.smartworksite.ai.dto;

import com.xd.smartworksite.file.domain.DocumentLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceItemTest {

    @Test
    void carriesProjectScopeAndPreciseDocumentLocation() {
        EvidenceItem item = EvidenceItem.knowledgeDocument(
                7L, 10L, 99L, "chunk-1",
                new DocumentLocation(2, "风险台账", 4, "A3:F8", null),
                "一级风险：临边防护缺失");

        assertThat(item.getProjectId()).isEqualTo(7L);
        assertThat(item.getKnowledgeBaseId()).isEqualTo(10L);
        assertThat(item.getDocumentId()).isEqualTo(99L);
        assertThat(item.getLocation().getPage()).isEqualTo(2);
        assertThat(item.getLocation().getSheet()).isEqualTo("风险台账");
        assertThat(item.getExcerpt()).isEqualTo("一级风险：临边防护缺失");
        assertThat(item.getColumnNames()).isEmpty();
        assertThat(item.getMetadata()).isEmpty();
    }

    @Test
    void databaseEvidenceCarriesReadOnlyQueryAndColumns() {
        EvidenceItem item = EvidenceItem.database(
                7L, 21L, "risk_warning", List.of("risk_level", "owner"),
                "SELECT risk_level, owner FROM risk_warning", "一级风险 2 条", Map.of("rowCount", 2));

        assertThat(item.getSourceType()).isEqualTo(EvidenceItem.SourceType.DATABASE);
        assertThat(item.getDataSourceId()).isEqualTo(21L);
        assertThat(item.getTableName()).isEqualTo("risk_warning");
        assertThat(item.getColumnNames()).containsExactly("risk_level", "owner");
        assertThat(item.getReadOnlySql()).startsWith("SELECT");
        assertThat(item.getMetadata()).containsEntry("rowCount", 2);
        assertThat(item.getKnowledgeBaseId()).isNull();
    }
}
