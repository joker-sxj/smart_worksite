package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.DocumentBlock;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PowerPointDocumentParserTest {

    @Test
    void readsTwoColumnSlidesDownTheLeftColumnBeforeTheRightColumn() throws Exception {
        byte[] content;
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            addText(slide, "左栏标题", 20, 40);
            addText(slide, "右栏标题", 390, 40);
            addText(slide, "左栏正文", 20, 120);
            addText(slide, "右栏正文", 390, 120);
            show.write(output);
            content = output.toByteArray();
        }

        PreparedDocument document = new PowerPointDocumentParser(new FileProperties())
                .parse(fileObject(7L, 20L), content);

        assertThat(document.getBlocks()).extracting(DocumentBlock::getText)
                .containsExactly("左栏标题", "左栏正文", "右栏标题", "右栏正文");
    }

    @Test
    void preservesSlideReadingOrderTablesAndNotes() throws Exception {
        byte[] content;
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox title = slide.createTextBox();
            title.setText("安全月报");
            title.setAnchor(new Rectangle(20, 20, 300, 40));
            XSLFTextBox body = slide.createTextBox();
            body.setText("先检查临边防护，再检查塔吊。");
            body.setAnchor(new Rectangle(20, 80, 500, 80));
            XSLFTable table = slide.createTable();
            XSLFTableRow header = table.addRow();
            header.addCell().setText("风险等级");
            header.addCell().setText("负责人");
            XSLFTableRow data = table.addRow();
            data.addCell().setText("一级");
            data.addCell().setText("张三");
            table.setAnchor(new Rectangle(20, 180, 500, 120));
            setNotes(show, slide, "汇报时重点说明一级风险整改期限。");
            show.write(output);
            content = output.toByteArray();
        }

        FileProperties properties = new FileProperties();
        properties.getParse().setMaxSlides(20);
        PowerPointDocumentParser parser = new PowerPointDocumentParser(properties);
        PreparedDocument document = parser.parse(fileObject(7L, 21L), content);

        assertThat(document.getProjectId()).isEqualTo(7L);
        assertThat(document.getDocumentId()).isEqualTo(21L);
        assertThat(document.getPageCount()).isEqualTo(1);
        assertThat(document.getBlocks()).extracting(DocumentBlock::getType)
                .containsExactly(DocumentBlock.Type.TEXT, DocumentBlock.Type.TEXT,
                        DocumentBlock.Type.TABLE, DocumentBlock.Type.TEXT);
        assertThat(document.getBlocks()).extracting(DocumentBlock::getText)
                .containsExactly("安全月报", "先检查临边防护，再检查塔吊。",
                        "风险等级\t负责人\n一级\t张三", "汇报时重点说明一级风险整改期限。");
        assertThat(document.getBlocks()).allSatisfy(block ->
                assertThat(block.getLocation().getSlide()).isEqualTo(1));
        assertThat(document.getBlocks().get(2).getStructuredData()).containsEntry("rowCount", 2);
        assertThat(document.getBlocks().get(2).getStructuredData().get("rows")).isInstanceOf(java.util.List.class);
        assertThat(document.getBlocks().get(3).getStructuredData()).containsEntry("notes", true);
        assertThat(document.getBlocks().get(0).getStructuredData()).containsEntry("readingOrder", 0);
    }

    @Test
    void boundsModelTextWithoutDiscardingStructuredPresentationEvidence() throws Exception {
        byte[] content;
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox body = slide.createTextBox();
            body.setText("一级风险整改负责人张三");
            show.write(output);
            content = output.toByteArray();
        }
        FileProperties properties = new FileProperties();
        properties.getParse().setMaxInputChars(6);

        PreparedDocument document = new PowerPointDocumentParser(properties)
                .parse(fileObject(7L, 24L), content);

        assertThat(document.isTruncated()).isTrue();
        assertThat(document.getTextContent()).hasSize(6);
        assertThat(document.getBlocks()).singleElement()
                .extracting(DocumentBlock::getText)
                .isEqualTo("一级风险整改负责人张三");
    }
    @Test
    void rejectsPresentationBeyondConfiguredSlideLimit() throws Exception {
        byte[] content;
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            show.createSlide();
            show.createSlide();
            show.write(output);
            content = output.toByteArray();
        }
        FileProperties properties = new FileProperties();
        properties.getParse().setMaxSlides(1);
        PowerPointDocumentParser parser = new PowerPointDocumentParser(properties);

        assertThatThrownBy(() -> parser.parse(fileObject(7L, 22L), content))
                .hasMessageContaining("slide count");
    }


    @Test
    void ordersShapesByVisualPositionInsteadOfInsertionOrder() throws Exception {
        byte[] content;
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox lower = slide.createTextBox();
            lower.setText("下方内容");
            lower.setAnchor(new Rectangle(20, 200, 300, 40));
            XSLFTextBox upper = slide.createTextBox();
            upper.setText("上方内容");
            upper.setAnchor(new Rectangle(20, 20, 300, 40));
            show.write(output);
            content = output.toByteArray();
        }

        PreparedDocument document = new PowerPointDocumentParser(new FileProperties())
                .parse(fileObject(7L, 25L), content);

        assertThat(document.getBlocks()).extracting(DocumentBlock::getText)
                .containsExactly("上方内容", "下方内容");
        assertThat(document.getBlocks()).extracting(block -> block.getStructuredData().get("readingOrder"))
                .containsExactly(0, 1);
    }

    @Test
    void recursivelyExtractsGroupedTextAndCountsNestedShapes() throws Exception {
        byte[] content;
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            var group = slide.createGroup();
            XSLFTextBox first = group.createTextBox();
            first.setText("分组标题");
            first.setAnchor(new Rectangle(20, 20, 200, 40));
            XSLFTextBox second = group.createTextBox();
            second.setText("分组正文");
            second.setAnchor(new Rectangle(20, 80, 300, 40));
            show.write(output);
            content = output.toByteArray();
        }
        FileProperties properties = new FileProperties();
        properties.getParse().setMaxPresentationShapes(3);

        PreparedDocument document = new PowerPointDocumentParser(properties)
                .parse(fileObject(7L, 28L), content);

        assertThat(document.getBlocks()).extracting(DocumentBlock::getText)
                .containsExactly("分组标题", "分组正文");
    }
    @Test
    void rejectsPresentationsBeyondConfiguredShapeLimit() throws Exception {
        byte[] content;
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            slide.createTextBox().setText("一");
            slide.createTextBox().setText("二");
            show.write(output);
            content = output.toByteArray();
        }
        FileProperties properties = new FileProperties();
        properties.getParse().setMaxPresentationShapes(1);

        assertThatThrownBy(() -> new PowerPointDocumentParser(properties)
                .parse(fileObject(7L, 26L), content))
                .hasMessageContaining("shape limit");
    }

    @Test
    void rejectsPresentationsBeyondConfiguredTableCellLimit() throws Exception {
        byte[] content;
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            org.apache.poi.xslf.usermodel.XSLFTable table = slide.createTable(2, 2);
            table.getCell(0, 0).setText("1");
            table.getCell(0, 1).setText("2");
            table.getCell(1, 0).setText("3");
            table.getCell(1, 1).setText("4");
            show.write(output);
            content = output.toByteArray();
        }
        FileProperties properties = new FileProperties();
        properties.getParse().setMaxPresentationCells(3);

        assertThatThrownBy(() -> new PowerPointDocumentParser(properties)
                .parse(fileObject(7L, 27L), content))
                .hasMessageContaining("table cell limit");
    }

    @Test
    void parsesLegacyPptText() throws Exception {
        byte[] content;
        try (org.apache.poi.hslf.usermodel.HSLFSlideShow show = new org.apache.poi.hslf.usermodel.HSLFSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            org.apache.poi.hslf.usermodel.HSLFSlide slide = show.createSlide();
            org.apache.poi.hslf.usermodel.HSLFTextBox textBox = slide.createTextBox();
            textBox.setText("旧版施工进度汇报");
            textBox.setAnchor(new Rectangle(20, 20, 400, 60));
            show.write(output);
            content = output.toByteArray();
        }
        FileProperties properties = new FileProperties();
        PowerPointDocumentParser parser = new PowerPointDocumentParser(properties);
        FileObject file = fileObject(7L, 23L);
        file.setFileName("legacy.ppt");
        file.setFileExt("ppt");
        file.setContentType("application/vnd.ms-powerpoint");

        PreparedDocument document = parser.parse(file, content);

        assertThat(document.getInputFormat()).isEqualTo("ppt");
        assertThat(document.getTextContent()).contains("旧版施工进度汇报");
    }
    private void setNotes(XMLSlideShow show, XSLFSlide slide, String text) {
        XSLFNotes notes = show.getNotesSlide(slide);
        for (XSLFShape shape : notes.getShapes()) {
            if (shape instanceof XSLFTextShape textShape
                    && textShape.getTextType() == Placeholder.BODY) {
                textShape.setText(text);
                return;
            }
        }
        throw new IllegalStateException("notes body placeholder not found");
    }

    private void addText(XSLFSlide slide, String text, int x, int y) {
        XSLFTextBox box = slide.createTextBox();
        box.setText(text);
        box.setAnchor(new Rectangle(x, y, 300, 50));
    }

    private FileObject fileObject(Long projectId, Long id) {
        FileObject fileObject = new FileObject();
        fileObject.setProjectId(projectId);
        fileObject.setId(id);
        fileObject.setFileName("report.pptx");
        fileObject.setFileExt("pptx");
        fileObject.setContentType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        return fileObject;
    }
}
