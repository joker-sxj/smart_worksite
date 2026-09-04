package com.xd.smartworksite.report.application;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

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
}
