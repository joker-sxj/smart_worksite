package com.xd.smartworksite.knowledge.infra;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentPersistenceContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mapper/knowledge/KnowledgeDocumentMapper.xml");
    private static final Path FILE_METADATA_MIGRATION = Path.of(
            "src/main/resources/db/migration/V33__add_knowledge_document_file_metadata.sql");

    @Test
    void pagedDocumentQueryDeclaresAliasUsedByFiltersAndOrdering() throws Exception {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml).contains("from knowledge_document kd");
        assertThat(xml).contains("and kd.index_status = #{indexStatus}");
        assertThat(xml).contains("and kd.title like concat('%', #{keyword}, '%')");
        assertThat(xml).contains("order by kd.created_at desc, kd.id desc");
    }

    @Test
    void documentPersistenceStoresAndReturnsUploadedFileMetadata() throws Exception {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml).contains("file_id, title, file_ext, content_type, source_type");
        assertThat(xml).contains("#{fileId}, #{title}, #{fileExt}, #{contentType}, #{sourceType}");
        assertThat(Files.readString(FILE_METADATA_MIGRATION, StandardCharsets.UTF_8))
                .contains("ADD COLUMN file_ext")
                .contains("ADD COLUMN content_type");
    }
}
