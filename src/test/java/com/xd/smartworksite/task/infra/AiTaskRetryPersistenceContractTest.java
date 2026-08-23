package com.xd.smartworksite.task.infra;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class AiTaskRetryPersistenceContractTest {
    private static final Path QA_MAPPER = Path.of("src/main/resources/mapper/qa/QaMapper.xml");
    private static final Path REVIEW_MAPPER = Path.of("src/main/resources/mapper/review/ReviewRecordMapper.xml");
    @Test
    void genericTaskRetryCanResumeFailedQaAndReviewBusinessRecords() throws Exception {
        String qaXml = Files.readString(QA_MAPPER, StandardCharsets.UTF_8);
        String reviewXml = Files.readString(REVIEW_MAPPER, StandardCharsets.UTF_8);
        assertThat(updateStatement(qaXml, "markMessageProcessing")).contains("'FAILED'");
        assertThat(updateStatement(reviewXml, "markProcessing")).contains("'FAILED'");
    }
    private String updateStatement(String xml, String id) {
        int start = xml.indexOf("<update id=\"" + id + "\">");
        int end = xml.indexOf("</update>", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
