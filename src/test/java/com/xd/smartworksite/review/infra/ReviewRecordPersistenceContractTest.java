package com.xd.smartworksite.review.infra;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewRecordPersistenceContractTest {
    private static final Path MAPPER = Path.of("src/main/resources/mapper/review/ReviewRecordMapper.xml");
    private static final Path TEMPLATE_SNAPSHOT_MIGRATION = Path.of(
            "src/main/resources/db/migration/V34__snapshot_review_template_identity.sql");

    @Test
    void issueUpdatesPreserveCompletedOrPartialSuccessStatus() throws Exception {
        String statement = updateStatement(Files.readString(MAPPER, StandardCharsets.UTF_8), "updateIssues");

        assertThat(statement).contains("status in ('PARTIAL_SUCCESS', 'COMPLETED')");
        assertThat(statement).doesNotContain("set status =");
    }

    @Test
    void templateIdentityIsSnapshottedAndHistoricalRowsAreBackfilled() throws Exception {
        String mapper = Files.readString(MAPPER, StandardCharsets.UTF_8);
        String migration = Files.readString(TEMPLATE_SNAPSHOT_MIGRATION, StandardCharsets.UTF_8);

        assertThat(mapper).contains("template_name, template_version");
        assertThat(mapper).contains("#{templateName}, #{templateVersion}");
        assertThat(migration).contains("ADD COLUMN template_name", "ADD COLUMN template_version");
        assertThat(migration).contains("JOIN template t ON t.id = r.template_id");
        assertThat(migration).contains("r.template_version = t.version_no");
    }

    private String updateStatement(String xml, String id) {
        int start = xml.indexOf("<update id=\"" + id + "\">");
        int end = xml.indexOf("</update>", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
