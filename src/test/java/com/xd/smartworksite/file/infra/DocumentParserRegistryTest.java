package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.file.domain.DocumentLocation;
import com.xd.smartworksite.file.domain.PreparedDocument;
import com.xd.smartworksite.file.domain.FileObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserRegistryTest {

    @Test
    void routesByExtensionBeforeMimeFallbackAndPreservesBlockOrder() {
        DocumentParser pdf = new TestParser("pdf", SetSupport.of("pdf"), SetSupport.of("application/pdf"));
        DocumentParser excel = new TestParser("xlsx", SetSupport.of("xlsx"), SetSupport.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(pdf, excel));

        assertThat(registry.resolve("report.xlsx", "application/octet-stream")).isSameAs(excel);
        assertThat(registry.resolve("report", "application/pdf")).isSameAs(pdf);

        PreparedDocument prepared = PreparedDocument.forFile(
                7L, 11L, "xlsx",
                List.of(
                        DocumentBlockTestData.text("text-1", DocumentLocation.sheet("风险台账", "A1:B2")),
                        DocumentBlockTestData.table("table-1", DocumentLocation.sheet("风险台账", "A3:F8"))
                ), 0, false);
        assertThat(prepared.getProjectId()).isEqualTo(7L);
        assertThat(prepared.getDocumentId()).isEqualTo(11L);
        assertThat(prepared.getBlocks()).extracting("blockId").containsExactly("text-1", "table-1");
        assertThat(prepared.getBlocks().get(1).getLocation().getSheet()).isEqualTo("风险台账");
        assertThat(prepared.getBlocks().get(1).getLocation().getCellRange()).isEqualTo("A3:F8");
    }

    @Test
    void rejectsUnsupportedExtensionAndMime() {
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(
                new TestParser("pdf", SetSupport.of("pdf"), SetSupport.of("application/pdf"))));
        assertThat(registry.find("archive.bin", "application/octet-stream")).isEmpty();
    }

    private record TestParser(String format, SetSupport extensions, SetSupport mimeTypes) implements DocumentParser {
        @Override
        public boolean supports(String fileExt, String contentType) {
            return extensions.contains(fileExt) || mimeTypes.contains(contentType);
        }

        @Override
        public PreparedDocument parse(FileObject fileObject, byte[] content) {
            return PreparedDocument.text(format, "test", 0, false);
        }
    }

    private record SetSupport(java.util.Set<String> values) {
        static SetSupport of(String value) { return new SetSupport(java.util.Set.of(value)); }
        boolean contains(String value) { return values.contains(value); }
    }

    private static final class DocumentBlockTestData {
        private static com.xd.smartworksite.file.domain.DocumentBlock text(String id, DocumentLocation location) {
            return com.xd.smartworksite.file.domain.DocumentBlock.text(id, "text", location);
        }

        private static com.xd.smartworksite.file.domain.DocumentBlock table(String id, DocumentLocation location) {
            return com.xd.smartworksite.file.domain.DocumentBlock.table(id, "table", Map.of("rows", List.of(List.of("risk"))), location);
        }
    }
}
