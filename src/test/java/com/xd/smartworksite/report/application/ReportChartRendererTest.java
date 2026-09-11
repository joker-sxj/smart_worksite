package com.xd.smartworksite.report.application;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportChartRendererTest {
    private final ReportChartRenderer renderer = new ReportChartRenderer();

    @Test
    void rendersAllowlistedBarPieAndLinePngCharts() {
        Map<String, Integer> values = new LinkedHashMap<>(Map.of("一级", 2, "二级", 1));

        assertThat(renderer.render("BAR", values)).startsWith(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
        assertThat(renderer.render("PIE", values)).startsWith(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
        assertThat(renderer.render("LINE", values)).startsWith(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
    }

    @Test
    void rejectsUnsupportedOrEmptyCharts() {
        assertThatThrownBy(() -> renderer.render("SCATTER", Map.of("x", 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不支持");
        assertThatThrownBy(() -> renderer.render("BAR", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没有可绘制");
    }

    @Test
    void rendersReadableLabelsAndDoesNotStretchOneBarAcrossThePlot() throws Exception {
        var spec = new ReportChartPlanner().plan(
                new com.xd.smartworksite.report.domain.ReportStatistics(1, 1,
                        Map.of("risk_level", Map.of("一级", 1)), Map.of(), Map.of()), "数据源 1");

        byte[] png = renderer.render(spec);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int bluePixels = 0;
        int blueColumns = 0;
        for (int x = 100; x < image.getWidth() - 80; x++) {
            boolean hasBlue = false;
            for (int y = 130; y < 570; y++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                if (red < 80 && green > 100 && green < 175 && blue > 105 && blue < 190) {
                    bluePixels++;
                    hasBlue = true;
                }
            }
            if (hasBlue) blueColumns++;
        }

        assertThat(bluePixels).isGreaterThan(1000);
        assertThat(blueColumns).isLessThan(500);
    }
}
