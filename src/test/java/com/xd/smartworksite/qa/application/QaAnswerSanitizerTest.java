package com.xd.smartworksite.qa.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QaAnswerSanitizerTest {
    private final QaAnswerSanitizer sanitizer = new QaAnswerSanitizer();

    @Test
    void removesClosedThinkBlockAndKeepsFinalAnswer() {
        String raw = "<think>内部分析与检索上下文</think>\n\n最终回答";

        assertThat(sanitizer.sanitize(raw)).isEqualTo("最终回答");
    }

    @Test
    void removesLeakedPlanningPrefixBeforeThinkEndMarker() {
        String raw = "我们需要回答用户：先分析资料。Let's final. </think>\n正式结论";

        assertThat(sanitizer.sanitize(raw)).isEqualTo("正式结论");
    }

    @Test
    void preservesNormalAnswerContainingTheWordThink() {
        String raw = "结论：think 标签不应出现在最终回答中。";

        assertThat(sanitizer.sanitize(raw)).isEqualTo(raw);
    }

    @Test
    void removesUnclosedReasoningBlock() {
        assertThat(sanitizer.sanitize("<think>private reasoning without closing tag")).isEmpty();
    }

}