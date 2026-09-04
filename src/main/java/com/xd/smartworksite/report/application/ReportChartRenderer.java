package com.xd.smartworksite.report.application;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
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
}
