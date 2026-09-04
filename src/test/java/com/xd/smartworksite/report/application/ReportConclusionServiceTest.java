package com.xd.smartworksite.report.application;

import com.xd.smartworksite.report.domain.ReportStatistics;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportConclusionServiceTest {
    private final ReportConclusionService service = new ReportConclusionService();
    private final ReportStatistics facts = new ReportStatistics(
            3, 3, Map.of("risk_level", Map.of("一级", 2, "二级", 1)),
            Map.of("2026-08", 2, "2026-09", 1), Map.of("amount", 30.0));

    @Test
    void acceptsLocalModelConclusionWhenEveryNumberIsGrounded() {
        var result = service.resolve(facts, "共3条记录，一级风险2条，二级风险1条，金额合计30.0。");

        assertThat(result.text()).startsWith("共3条记录");
        assertThat(result.fallback()).isFalse();
        assertThat(result.fallbackReason()).isNull();
    }

    @Test
    void fallsBackWhenModelAddsUnsupportedNumber() {
        var result = service.resolve(facts, "共3条记录，明天将新增99条风险。");

        assertThat(result.text()).isEqualTo(service.standardConclusion(facts));
        assertThat(result.fallback()).isTrue();
        assertThat(result.fallbackReason()).isEqualTo("MODEL_NUMBER_CONFLICT");
    }

    @Test
    void neverLetsEmptyFactsProducePositiveConclusion() {
        var empty = new ReportStatistics(0, 0, Map.of(), Map.of(), Map.of());

        var result = service.resolve(empty, "当前无风险，全部闭环。");

        assertThat(result.fallback()).isTrue();
        assertThat(result.text()).contains("未返回可用于统计的记录")
                .doesNotContain("无风险").doesNotContain("全部闭环");
    }
}
