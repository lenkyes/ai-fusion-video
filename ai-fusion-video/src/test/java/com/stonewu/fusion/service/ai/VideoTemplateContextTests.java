package com.stonewu.fusion.service.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VideoTemplateContextTests {

    @Test
    void keepsDurationRuleWhenTemplateIsAbsent() {
        assertThat(VideoTemplateContext.buildStoryboardRule(Map.of("storyboardMode", "regular")))
                .isEqualTo(StoryboardDurationContext.buildPromptRule(Map.of("storyboardMode", "regular")));
    }

    @Test
    void appendsTemplatePromptToStoryboardRule() {
        String rule = VideoTemplateContext.buildStoryboardRule(Map.of(
                "storyboardMode", "regular",
                "templatePrompt", "必须使用第一人称 POV 镜头和第一人称念白"
        ));

        assertThat(rule).contains("视频创作模板的强制约束");
        assertThat(rule).contains("第一人称 POV");
    }

    @Test
    void limitsUntrustedTemplatePromptLength() {
        String rule = VideoTemplateContext.buildStoryboardRule(Map.of(
                "storyboardMode", "regular",
                "templatePrompt", "x".repeat(20_000)
        ));

        assertThat(rule.chars().filter(value -> value == 'x').count()).isEqualTo(12_000);
    }
}
