package com.xd.smartworksite.file.infra;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class FileParsePersistenceContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V21__enforce_active_file_parse_uniqueness.sql");
    private static final Path MAPPER = Path.of(
            "src/main/resources/mapper/file/FileParseRecordMapper.xml");

    @Test
    void activeParseIdentityUsesBoundedIndexWidthAndPreservesNullIdentity() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql).contains("active_identity varchar(256)");
        assertThat(sql).contains("source_file_hash is null");
        assertThat(sql).contains("if(source_file_hash is null, 'n:', concat('v:', source_file_hash))");
        assertThat(sql).doesNotContain("active_identity varchar(700)");
    }

    @Test
    void activeAndReusableQueriesUseNullSafeSourceHashComparison() throws Exception {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml.split(Pattern.quote("source_file_hash &lt;=&gt; #{sourceFileHash}"), -1)).hasSize(3);
        assertThat(xml).contains("status in ('PENDING', 'PARSING', 'RUNNING')");
    }
}
