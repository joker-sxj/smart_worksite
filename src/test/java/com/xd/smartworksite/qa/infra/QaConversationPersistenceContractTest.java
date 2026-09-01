package com.xd.smartworksite.qa.infra;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QaConversationPersistenceContractTest {
    @Test
    void sessionMemoryUpsertCannotOverwriteNewerCoverage() throws Exception {
        String mapper = Files.readString(Path.of("src/main/resources/mapper/qa/QaMapper.xml"));

        assertThat(mapper)
                .contains("values(covered_message_id) >= coalesce(covered_message_id, 0)")
                .contains("values(summary_json)");
    }
}
