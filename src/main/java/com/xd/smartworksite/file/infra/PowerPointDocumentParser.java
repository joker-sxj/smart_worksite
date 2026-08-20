package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.DocumentBlock;
import com.xd.smartworksite.file.domain.DocumentLocation;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;
import org.apache.poi.sl.usermodel.GroupShape;
import org.apache.poi.sl.usermodel.Notes;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.sl.usermodel.TableCell;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.sl.usermodel.TextShape.TextPlaceholder;
import org.springframework.stereotype.Component;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;

@Component
public class PowerPointDocumentParser implements DocumentParser {

    private static final Set<String> EXTENSIONS = Set.of("ppt", "pptx");
    private static final double COLUMN_GAP_THRESHOLD = 40D;

    private static final Set<String> CONTENT_TYPES = Set.of(
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    private final FileProperties fileProperties;

    public PowerPointDocumentParser(FileProperties fileProperties) {
        this.fileProperties = fileProperties;
    }

    @Override
    public boolean supports(String fileExt, String contentType) {
        return EXTENSIONS.contains(normalize(fileExt)) || CONTENT_TYPES.contains(normalize(contentType));
    }

    @Override
    public PreparedDocument parse(FileObject fileObject, byte[] content) {
        try (SlideShow<?, ?> show = SlideShowFactory.create(new ByteArrayInputStream(content))) {
            int slideCount = show.getSlides().size();
            if (slideCount > fileProperties.getParse().getMaxSlides()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "presentation slide count exceeds parse limit");
            }
            List<DocumentBlock> blocks = new ArrayList<>();
            int totalShapes = 0;
            int totalCells = 0;
            for (int slideIndex = 0; slideIndex < slideCount; slideIndex++) {
                SlideContent contentModel = readSlide(show.getSlides().get(slideIndex), slideIndex + 1,
                        fileProperties.getParse().getMaxPresentationShapes() - totalShapes,
                        fileProperties.getParse().getMaxPresentationCells() - totalCells,
                        show.getPageSize().getWidth());
                totalShapes += contentModel.shapeCount();
                totalCells += contentModel.cellCount();
                blocks.addAll(contentModel.blocks());
                if (totalShapes > fileProperties.getParse().getMaxPresentationShapes()) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "presentation shape limit exceeded");
                }
                if (totalCells > fileProperties.getParse().getMaxPresentationCells()) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "presentation table cell limit exceeded");
                }
            }
            if (blocks.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "presentation contains no readable content");
            }
            return PreparedDocument.forFile(fileObject.getProjectId(), fileObject.getId(),
                    inputFormat(fileObject), blocks, slideCount, false, fileProperties.getParse().getMaxInputChars());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "presentation parsing failed");
        }
    }

    private SlideContent readSlide(Slide<?, ?> slide, int slideNumber,
                                   int remainingShapes, int remainingCells, double pageWidth) {
        List<Shape<?, ?>> shapes = new ArrayList<>();
        int shapeCount = flattenShapes(slide.getShapes(), shapes, remainingShapes, 0);
        shapes = orderShapes(shapes, pageWidth);
        List<DocumentBlock> blocks = new ArrayList<>();
        int readingOrder = 0;
        int cellCount = 0;
        for (Shape<?, ?> shape : shapes) {
            if (shape instanceof TableShape<?, ?> table) {
                long tableCells = (long) table.getNumberOfRows() * table.getNumberOfColumns();
                if (tableCells > remainingCells - cellCount) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "presentation table cell limit exceeded");
                }
                TableContent tableContent = tableContent(table);
                cellCount += tableContent.cellCount();
                if (!tableContent.text().isBlank()) {
                    Map<String, Object> data = shapeMetadata(shape, readingOrder);
                    data.put("rows", tableContent.rows());
                    data.put("rowCount", tableContent.rows().size());
                    blocks.add(DocumentBlock.table(
                            "slide-" + slideNumber + "-shape-" + readingOrder,
                            tableContent.text(), data, location(slideNumber, shape)));
                }
            } else if (shape instanceof TextShape<?, ?> textShape) {
                String text = normalizeText(textShape.getText());
                if (!text.isBlank()) {
                    blocks.add(new DocumentBlock(
                            "slide-" + slideNumber + "-shape-" + readingOrder,
                            DocumentBlock.Type.TEXT,
                            text,
                            shapeMetadata(shape, readingOrder),
                            location(slideNumber, shape)
                    ));
                }
            }
            readingOrder++;
        }
        readNotes(slide.getNotes(), slideNumber, readingOrder, blocks);
        return new SlideContent(List.copyOf(blocks), shapeCount, cellCount);
    }

    private int flattenShapes(Iterable<? extends Shape<?, ?>> source,
                              List<Shape<?, ?>> leaves,
                              int remainingShapes,
                              int depth) {
        if (depth > 16) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "presentation group nesting limit exceeded");
        }
        int count = 0;
        for (Shape<?, ?> shape : source) {
            count++;
            if (count > remainingShapes) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "presentation shape limit exceeded");
            }
            if (shape instanceof GroupShape<?, ?> group) {
                int nested = flattenShapes(group.getShapes(), leaves, remainingShapes - count, depth + 1);
                count += nested;
                if (count > remainingShapes) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "presentation shape limit exceeded");
                }
            } else {
                leaves.add(shape);
            }
        }
        return count;
    }

    private List<Shape<?, ?>> orderShapes(List<Shape<?, ?>> shapes, double pageWidth) {
        Comparator<Shape<?, ?>> verticalOrder = Comparator.comparingDouble(this::y).thenComparingDouble(this::x);
        if (pageWidth <= 0) {
            shapes.sort(verticalOrder);
            return shapes;
        }

        double center = pageWidth / 2D;
        List<Shape<?, ?>> left = new ArrayList<>();
        List<Shape<?, ?>> right = new ArrayList<>();
        List<Shape<?, ?>> spanning = new ArrayList<>();
        for (Shape<?, ?> shape : shapes) {
            Rectangle2D anchor = shape.getAnchor();
            if (anchor == null || (anchor.getX() < center && anchor.getMaxX() > center)) {
                spanning.add(shape);
            } else if (anchor.getCenterX() < center) {
                left.add(shape);
            } else {
                right.add(shape);
            }
        }
        if (left.size() < 2 || right.size() < 2 || horizontalGap(left, right) < COLUMN_GAP_THRESHOLD) {
            shapes.sort(verticalOrder);
            return shapes;
        }

        left.sort(verticalOrder);
        right.sort(verticalOrder);
        spanning.sort(verticalOrder);
        List<Shape<?, ?>> ordered = new ArrayList<>(shapes.size());
        int leftIndex = 0;
        int rightIndex = 0;
        for (Shape<?, ?> divider : spanning) {
            double dividerY = y(divider);
            leftIndex = appendBefore(left, leftIndex, dividerY, ordered);
            rightIndex = appendBefore(right, rightIndex, dividerY, ordered);
            ordered.add(divider);
        }
        ordered.addAll(left.subList(leftIndex, left.size()));
        ordered.addAll(right.subList(rightIndex, right.size()));
        return ordered;
    }

    private double horizontalGap(List<Shape<?, ?>> left, List<Shape<?, ?>> right) {
        double leftEdge = left.stream().map(Shape::getAnchor).mapToDouble(Rectangle2D::getMaxX).max().orElse(0D);
        double rightEdge = right.stream().map(Shape::getAnchor).mapToDouble(Rectangle2D::getX).min().orElse(0D);
        return rightEdge - leftEdge;
    }

    private int appendBefore(List<Shape<?, ?>> source, int index, double yLimit, List<Shape<?, ?>> target) {
        while (index < source.size() && y(source.get(index)) < yLimit) {
            target.add(source.get(index++));
        }
        return index;
    }

    private double x(Shape<?, ?> shape) {
        Rectangle2D anchor = shape.getAnchor();
        return anchor == null ? Double.POSITIVE_INFINITY : anchor.getX();
    }

    private double y(Shape<?, ?> shape) {
        Rectangle2D anchor = shape.getAnchor();
        return anchor == null ? Double.POSITIVE_INFINITY : anchor.getY();
    }

    private TableContent tableContent(TableShape<?, ?> table) {
        List<List<String>> rows = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int rowIndex = 0; rowIndex < table.getNumberOfRows(); rowIndex++) {
            List<String> cells = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < table.getNumberOfColumns(); columnIndex++) {
                TableCell<?, ?> cell = table.getCell(rowIndex, columnIndex);
                cells.add(cell == null ? "" : normalizeText(cell.getText()));
            }
            rows.add(List.copyOf(cells));
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(String.join("\t", cells));
        }
        return new TableContent(List.copyOf(rows), text.toString(), table.getNumberOfRows() * table.getNumberOfColumns());
    }

    private void readNotes(Notes<?, ?> notes, int slideNumber, int readingOrder, List<DocumentBlock> blocks) {
        if (notes == null) {
            return;
        }
        List<String> paragraphs = new ArrayList<>();
        for (Object candidate : notes.getShapes()) {
            if (candidate instanceof TextShape<?, ?> textShape
                    && isNotesBody(textShape.getTextPlaceholder())) {
                String text = normalizeText(textShape.getText());
                if (!text.isBlank()) {
                    paragraphs.add(text);
                }
            }
        }
        String text = String.join("\n", paragraphs);
        if (!text.isBlank()) {
            blocks.add(new DocumentBlock(
                    "slide-" + slideNumber + "-notes",
                    DocumentBlock.Type.TEXT,
                    text,
                    Map.of("notes", true, "readingOrder", readingOrder),
                    DocumentLocation.slide(slideNumber)
            ));
        }
    }

    private boolean isNotesBody(TextPlaceholder placeholder) {
        return placeholder == TextPlaceholder.BODY || placeholder == TextPlaceholder.NOTES;
    }

    private Map<String, Object> shapeMetadata(Shape<?, ?> shape, int readingOrder) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("readingOrder", readingOrder);
        data.put("shapeType", shape.getClass().getSimpleName());
        return data;
    }

    private DocumentLocation location(int slideNumber, Shape<?, ?> shape) {
        Rectangle2D anchor = shape.getAnchor();
        Map<String, Object> box = anchor == null ? Map.of() : Map.of(
                "x", anchor.getX(),
                "y", anchor.getY(),
                "width", anchor.getWidth(),
                "height", anchor.getHeight()
        );
        return new DocumentLocation(null, null, slideNumber, null, box);
    }

    private String inputFormat(FileObject fileObject) {
        String ext = normalize(fileObject.getFileExt());
        return EXTENSIONS.contains(ext) ? ext : "pptx";
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private record TableContent(List<List<String>> rows, String text, int cellCount) {
    }

    private record SlideContent(List<DocumentBlock> blocks, int shapeCount, int cellCount) {
    }
}
