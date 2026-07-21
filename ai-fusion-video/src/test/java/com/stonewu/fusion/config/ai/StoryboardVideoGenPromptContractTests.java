package com.stonewu.fusion.config.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryboardVideoGenPromptContractTests {

    @Test
    void parentDispatcherMustNotExposeSceneDetailQueryTool() {
        AiAgentDefinition definition = new AiAgentRegistry().getByType("storyboard_video_gen");

        assertThat(definition).isNotNull();
        assertThat(definition.getToolNames())
                .containsExactlyInAnyOrder("get_project", "get_storyboard")
                .doesNotContain("get_storyboard_scene_items");
        assertThat(definition.getSubAgentTools())
                .extracting(AiAgentDefinition.SubAgentToolDef::getToolName)
                .isEqualTo(List.of("generate_storyboard_video"));
    }

    @Test
    void batchTargetsMustNotBeTruncatedBySceneQueryOrPreviousFailure() throws IOException {
        String prompt = loadPrompt();

        assertThat(prompt)
                .contains("禁止主 Agent 调用 `get_storyboard_scene_items`")
                .contains("绝不能覆盖、截断或替换主 Agent 的 `selectedStoryboardItemIds`")
                .contains("单个子 Agent 返回错误时都必须记录该镜头失败并继续处理")
                .contains("最终汇总的总处理数必须等于目标清单数量")
                .doesNotContain("上一镜头失败时停止后续镜头");
    }

    private String loadPrompt() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/agents/storyboard-video-gen.system.md")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
