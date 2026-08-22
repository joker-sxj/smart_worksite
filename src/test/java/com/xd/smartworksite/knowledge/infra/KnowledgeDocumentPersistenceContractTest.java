package com.xd.smartworksite.knowledge.infra;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentPersistenceContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mapper/knowledge/KnowledgeDocumentMapper.xml");

    @Test
    void pagedDocumentQueryDeclaresAliasUsedByFiltersAndOrdering() throws Exception {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml).contains("from knowledge_document kd");
        assertThat(xml).contains("and kd.index_status = #{indexStatus}");
        assertThat(xml).contains("and kd.title like concat('%', #{keyword}, '%')");
        assertThat(xml).contains("order by kd.created_at desc, kd.id desc");
    }
}
