package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.FileObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvDocumentParserTest {
    @Test
    void parsesQuotedMultilineCsvAsMarkdownTable() {
        FileObject file = new FileObject();
        file.setProjectId(1L); file.setId(2L); file.setFileName("risk.csv"); file.setFileExt("csv"); file.setContentType("text/csv");
        var result = new CsvDocumentParser(new FileProperties()).parse(file,
                "项目,问题\n一号工地,\"临边防护\n缺失\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(result.getTextContent()).contains("## CSV", "| 项目 | 问题 |", "临边防护 缺失");
        assertThat(result.getBlocks()).singleElement().satisfies(block -> assertThat(block.getStructuredData()).containsEntry("rowCount", 2));
    }
}
