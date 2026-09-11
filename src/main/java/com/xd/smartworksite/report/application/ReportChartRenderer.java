package com.xd.smartworksite.report.application;

import com.xd.smartworksite.report.domain.ReportChartSpec;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReportChartRenderer {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 675;

    public byte[] render(String type, Map<String, Integer> values) {
        if (type == null || !(type.equalsIgnoreCase("BAR") || type.equalsIgnoreCase("PIE") || type.equalsIgnoreCase("LINE"))) {
            throw new IllegalArgumentException("不支持的图表类型");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("没有可绘制的数据");
        }
        Map<String, Integer> bounded = new LinkedHashMap<>();
        values.entrySet().stream().limit(20).forEach(entry -> bounded.put(entry.getKey(), Math.max(0, entry.getValue())));
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            if (type.equalsIgnoreCase("PIE")) {
                drawPie(graphics, bounded);
            } else {
                drawAxes(graphics, bounded, type.equalsIgnoreCase("LINE"));
            }
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("图表编码失败", ex);
        }
    }

    public byte[] render(ReportChartSpec spec) {
        if (spec == null || !spec.drawable() || spec.values() == null || spec.values().isEmpty()) {
            throw new IllegalArgumentException("没有可绘制的业务图表");
        }
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configure(graphics);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            drawHeader(graphics, spec);
            if ("LINE".equalsIgnoreCase(spec.type())) drawReadableLine(graphics, spec);
            else drawReadableBars(graphics, spec);
            drawFooter(graphics, spec);
        } finally {
            graphics.dispose();
        }
        return encode(image);
    }

    private void drawAxes(Graphics2D graphics, Map<String, Integer> values, boolean line) {
        int left = 90, bottom = 570, top = 70, width = 1000;
        graphics.setColor(Color.DARK_GRAY);
        graphics.drawLine(left, top, left, bottom);
        graphics.drawLine(left, bottom, left + width, bottom);
        int max = Math.max(1, values.values().stream().mapToInt(Integer::intValue).max().orElse(1));
        int index = 0;
        int previousX = 0, previousY = 0;
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            int x = left + (index * width / Math.max(1, values.size() - 1));
            int y = bottom - (entry.getValue() * (bottom - top) / max);
            graphics.setColor(new Color(35, 119, 190));
            if (line) {
                if (index > 0) graphics.drawLine(previousX, previousY, x, y);
                graphics.fillOval(x - 5, y - 5, 10, 10);
            } else {
                int barWidth = Math.max(12, width / Math.max(1, values.size()) - 12);
                graphics.fillRect(x - barWidth / 2, y, barWidth, bottom - y);
            }
            previousX = x;
            previousY = y;
            index++;
        }
    }

    private void drawPie(Graphics2D graphics, Map<String, Integer> values) {
        int total = values.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) throw new IllegalArgumentException("没有可绘制的数据");
        int angle = 0, index = 0;
        Color[] colors = {new Color(35, 119, 190), new Color(239, 150, 45), new Color(40, 160, 130)};
        for (int value : values.values()) {
            int arc = index == values.size() - 1 ? 360 - angle : value * 360 / total;
            graphics.setColor(colors[index++ % colors.length]);
            graphics.fillArc(250, 100, 450, 450, angle, arc);
            angle += arc;
        }
    }

    private void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private void drawHeader(Graphics2D graphics, ReportChartSpec spec) {
        graphics.setColor(new Color(28, 50, 72));
        graphics.setFont(font(Font.BOLD, 36));
        graphics.drawString(spec.title(), 80, 58);
        graphics.setColor(new Color(90, 105, 118));
        graphics.setFont(font(Font.PLAIN, 20));
        graphics.drawString("统计维度：" + spec.dimensionLabel() + "    单位：" + spec.unit(), 80, 92);
    }

    private void drawReadableBars(Graphics2D graphics, ReportChartSpec spec) {
        int left = 105, right = 70, top = 135, bottom = 555;
        int plotWidth = WIDTH - left - right;
        int plotHeight = bottom - top;
        int max = Math.max(1, spec.values().values().stream().mapToInt(Integer::intValue).max().orElse(1));
        drawScale(graphics, left, top, bottom, plotWidth, max);
        int count = spec.values().size();
        int slot = plotWidth / Math.max(1, count);
        int barWidth = Math.min(110, Math.max(28, slot * 3 / 5));
        int index = 0;
        graphics.setFont(font(Font.PLAIN, count > 7 ? 16 : 19));
        for (Map.Entry<String, Integer> entry : spec.values().entrySet()) {
            int x = left + slot * index + (slot - barWidth) / 2;
            int barHeight = entry.getValue() * (plotHeight - 35) / max;
            int y = bottom - barHeight;
            graphics.setColor(new Color(24, 127, 139));
            graphics.fillRoundRect(x, y, barWidth, barHeight, 10, 10);
            graphics.setColor(new Color(28, 50, 72));
            String number = String.valueOf(entry.getValue());
            drawCentered(graphics, number, x + barWidth / 2, Math.max(top + 20, y - 10));
            graphics.setColor(new Color(55, 65, 75));
            drawCentered(graphics, shorten(entry.getKey(), count > 7 ? 7 : 10), x + barWidth / 2, bottom + 30);
            index++;
        }
    }

    private void drawReadableLine(Graphics2D graphics, ReportChartSpec spec) {
        int left = 105, right = 70, top = 135, bottom = 555;
        int plotWidth = WIDTH - left - right;
        int plotHeight = bottom - top;
        int max = Math.max(1, spec.values().values().stream().mapToInt(Integer::intValue).max().orElse(1));
        drawScale(graphics, left, top, bottom, plotWidth, max);
        int count = spec.values().size();
        int index = 0, previousX = 0, previousY = 0;
        graphics.setFont(font(Font.PLAIN, 18));
        for (Map.Entry<String, Integer> entry : spec.values().entrySet()) {
            int x = count == 1 ? left + plotWidth / 2 : left + index * plotWidth / (count - 1);
            int y = bottom - entry.getValue() * (plotHeight - 35) / max;
            graphics.setColor(new Color(24, 127, 139));
            if (index > 0) {
                graphics.setStroke(new java.awt.BasicStroke(4f));
                graphics.drawLine(previousX, previousY, x, y);
            }
            graphics.fillOval(x - 8, y - 8, 16, 16);
            graphics.setColor(new Color(28, 50, 72));
            drawCentered(graphics, String.valueOf(entry.getValue()), x, Math.max(top + 20, y - 14));
            drawCentered(graphics, entry.getKey(), x, bottom + 30);
            previousX = x;
            previousY = y;
            index++;
        }
    }

    private void drawScale(Graphics2D graphics, int left, int top, int bottom, int width, int max) {
        graphics.setFont(font(Font.PLAIN, 16));
        for (int tick = 0; tick <= 4; tick++) {
            int y = bottom - tick * (bottom - top) / 4;
            graphics.setColor(new Color(225, 230, 234));
            graphics.drawLine(left, y, left + width, y);
            graphics.setColor(new Color(90, 105, 118));
            String label = String.valueOf((int) Math.ceil(max * tick / 4.0));
            graphics.drawString(label, left - 45, y + 6);
        }
        graphics.setColor(new Color(90, 105, 118));
        graphics.drawLine(left, top, left, bottom);
        graphics.drawLine(left, bottom, left + width, bottom);
    }

    private void drawFooter(Graphics2D graphics, ReportChartSpec spec) {
        graphics.setFont(font(Font.PLAIN, 17));
        graphics.setColor(new Color(90, 105, 118));
        graphics.drawString("数据来源：" + (spec.source().isBlank() ? "未注明" : spec.source()), 80, 645);
    }

    private Font font(int style, int size) {
        String[] candidates = {"Noto Sans CJK SC", "Microsoft YaHei", "Droid Sans Fallback", Font.SANS_SERIF};
        java.util.Set<String> available = java.util.Set.of(java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String candidate : candidates) if (available.contains(candidate)) return new Font(candidate, style, size);
        return new Font(Font.SANS_SERIF, style, size);
    }

    private void drawCentered(Graphics2D graphics, String value, int centerX, int baselineY) {
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(value, centerX - metrics.stringWidth(value) / 2, baselineY);
    }

    private String shorten(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }

    private byte[] encode(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("图表编码失败", ex);
        }
    }
}
