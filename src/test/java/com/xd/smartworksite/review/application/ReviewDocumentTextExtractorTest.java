package com.xd.smartworksite.review.application;

import com.xd.smartworksite.file.application.FileObjectContent;
import com.xd.smartworksite.file.domain.DocumentBlock;
import com.xd.smartworksite.file.domain.DocumentLocation;
import com.xd.smartworksite.file.domain.PreparedDocument;
import com.xd.smartworksite.file.infra.DocumentParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewDocumentTextExtractorTest {

    @Test
    void delegatesPdfToSharedParserSoScannedPagesCanUseOcrFallback() {
        DocumentParser parser = new DocumentParser() {
            @Override
            public boolean supports(String fileExt, String contentType) {
                return "pdf".equals(fileExt) || "application/pdf".equals(contentType);
            }

            @Override
            public PreparedDocument parse(com.xd.smartworksite.file.domain.FileObject fileObject, byte[] content) {
                return PreparedDocument.forFile(fileObject.getProjectId(), fileObject.getId(), "pdf", List.of(
                        DocumentBlock.text("page-1", "OCR review template", DocumentLocation.page(1))),
                        1, true);
            }
        };
        ReviewDocumentTextExtractor extractor = new ReviewDocumentTextExtractor(List.of(parser));
        FileObjectContent content = new FileObjectContent(
                9L, 7L, null, "template.pdf", "application/pdf", 3,
                new ByteArrayInputStream(new byte[]{1, 2, 3}));

        ReviewDocumentTextExtractor.ExtractedText result = extractor.extract(content);

        assertThat(result.text()).isEqualTo("OCR review template");
        assertThat(result.truncated()).isTrue();
    }
}
