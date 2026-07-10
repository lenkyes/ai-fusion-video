package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SaveStoryboardSceneShotsToolExecutorTests {

    private static final String MIXED_DURATION_INPUT = """
            {
              "storyboardId": 11,
              "storyboardEpisodeId": 22,
              "sceneNumber": "1-1",
              "shots": [
                {"content": "shot one", "duration": 3.5},
                {"content": "shot two"},
                {"content": "shot three", "duration": 8}
              ]
            }
            """;

    @Test
    void customModeOverridesEveryShotWithConfiguredDuration() {
        StoryboardService storyboardService = mock(StoryboardService.class);
        when(storyboardService.createScene(any(StoryboardScene.class)))
                .thenAnswer(invocation -> {
                    StoryboardScene scene = invocation.getArgument(0);
                    scene.setId(33L);
                    return scene;
                });
        SaveStoryboardSceneShotsToolExecutor executor =
                new SaveStoryboardSceneShotsToolExecutor(storyboardService);

        JSONObject result = JSONUtil.parseObj(executor.execute(
                MIXED_DURATION_INPUT,
                context(Map.of(
                        "storyboardMode", "custom",
                        "shotDuration", 15))));

        assertThat(result.getStr("status")).isEqualTo("success");
        ArgumentCaptor<List<StoryboardItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(storyboardService).batchCreateItems(itemsCaptor.capture());
        assertThat(itemsCaptor.getValue())
                .hasSize(3)
                .extracting(StoryboardItem::getDuration)
                .containsOnly(new BigDecimal("15"));
    }

    @Test
    void regularModePreservesPerShotDurations() {
        StoryboardService storyboardService = mock(StoryboardService.class);
        when(storyboardService.createScene(any(StoryboardScene.class)))
                .thenAnswer(invocation -> {
                    StoryboardScene scene = invocation.getArgument(0);
                    scene.setId(33L);
                    return scene;
                });
        SaveStoryboardSceneShotsToolExecutor executor =
                new SaveStoryboardSceneShotsToolExecutor(storyboardService);

        JSONObject result = JSONUtil.parseObj(executor.execute(
                MIXED_DURATION_INPUT,
                context(Map.of(
                        "storyboardMode", "regular",
                        "shotDuration", 15))));

        assertThat(result.getStr("status")).isEqualTo("success");
        ArgumentCaptor<List<StoryboardItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(storyboardService).batchCreateItems(itemsCaptor.capture());
        assertThat(itemsCaptor.getValue())
                .extracting(StoryboardItem::getDuration)
                .containsExactly(new BigDecimal("3.5"), null, new BigDecimal("8"));
    }

    @ParameterizedTest(name = "defaults to regular mode for legacy context: {0}")
    @MethodSource("legacyRegularContexts")
    void missingModeDefaultsToRegular(
            String caseName,
            ToolExecutionContext toolContext) {
        StoryboardService storyboardService = mock(StoryboardService.class);
        when(storyboardService.createScene(any(StoryboardScene.class)))
                .thenAnswer(invocation -> {
                    StoryboardScene scene = invocation.getArgument(0);
                    scene.setId(33L);
                    return scene;
                });
        SaveStoryboardSceneShotsToolExecutor executor =
                new SaveStoryboardSceneShotsToolExecutor(storyboardService);

        JSONObject result = JSONUtil.parseObj(executor.execute(MIXED_DURATION_INPUT, toolContext));

        assertThat(result.getStr("status")).as(caseName).isEqualTo("success");
        ArgumentCaptor<List<StoryboardItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(storyboardService).batchCreateItems(itemsCaptor.capture());
        assertThat(itemsCaptor.getValue())
                .extracting(StoryboardItem::getDuration)
                .containsExactly(new BigDecimal("3.5"), null, new BigDecimal("8"));
    }

    @Test
    void unknownModeIsRejectedBeforeCreatingScene() {
        StoryboardService storyboardService = mock(StoryboardService.class);
        SaveStoryboardSceneShotsToolExecutor executor =
                new SaveStoryboardSceneShotsToolExecutor(storyboardService);

        JSONObject result = JSONUtil.parseObj(executor.execute(
                MIXED_DURATION_INPUT,
                context(Map.of(
                        "storyboardMode", "unknown",
                        "shotDuration", 15))));

        assertThat(result.getStr("status")).isEqualTo("error");
        verifyNoInteractions(storyboardService);
    }

    @ParameterizedTest(name = "rejects invalid custom duration: {0}")
    @MethodSource("invalidCustomContexts")
    void invalidCustomDurationIsRejectedBeforeCreatingScene(
            String caseName,
            Map<String, Object> requestContext) {
        StoryboardService storyboardService = mock(StoryboardService.class);
        SaveStoryboardSceneShotsToolExecutor executor =
                new SaveStoryboardSceneShotsToolExecutor(storyboardService);

        JSONObject result = JSONUtil.parseObj(executor.execute(
                MIXED_DURATION_INPUT,
                context(requestContext)));

        assertThat(result.getStr("status")).as(caseName).isEqualTo("error");
        verifyNoInteractions(storyboardService);
    }

    private static Stream<Arguments> invalidCustomContexts() {
        return Stream.of(
                Arguments.of("missing", Map.of("storyboardMode", "custom")),
                Arguments.of("zero", customContext(0)),
                Arguments.of("above maximum", customContext(61)),
                Arguments.of("fractional", customContext(15.5)),
                Arguments.of("non numeric", customContext("fifteen"))
        );
    }

    private static Stream<Arguments> legacyRegularContexts() {
        return Stream.of(
                Arguments.of("empty context", ToolExecutionContext.builder().userId(1L).build()),
                Arguments.of("missing mode", context(Map.of("shotDuration", 15)))
        );
    }

    private static Map<String, Object> customContext(Object shotDuration) {
        return Map.of(
                "storyboardMode", "custom",
                "shotDuration", shotDuration);
    }

    private static ToolExecutionContext context(Map<String, Object> requestContext) {
        return ToolExecutionContext.builder()
                .userId(1L)
                .requestContext(requestContext)
                .build();
    }
}
