package com.stonewu.fusion.service.ai.tool;

import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.generation.consumer.VideoGenerationConsumer;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategyRouter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateVideoToolExecutorTests {

    @Test
    void usesConfiguredWaitTimeoutForAgentVideoGeneration() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);

        AiModel model = AiModel.builder()
                .id(31L)
                .status(1)
                .code("seedance")
                .build();
        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);

        VideoTask completedTask = VideoTask.builder().id(91L).taskId("task-91").status(2).build();
        when(videoGenerationConsumer.submitAndWait(any(VideoTask.class), eq(12345L))).thenReturn(completedTask);
        when(videoGenerationService.listItems(91L)).thenReturn(List.of(VideoItem.builder()
                .videoUrl("https://example.test/video.mp4")
                .coverUrl("https://example.test/cover.jpg")
                .duration(5)
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter);
        ReflectionTestUtils.setField(executor, "waitTimeoutMs", 12345L);

        String result = executor.execute("{\"prompt\":\"镜头缓慢推进\",\"duration\":5}",
                ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(12345L));
        assertThat(taskCaptor.getValue().getModelId()).isEqualTo(31L);
        assertThat(result).contains("\"status\":\"success\"");
        assertThat(result).contains("video.mp4");
    }

    @Test
    void skipsUnsupportedDefaultVideoModelAndUsesSupportedFallback() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);

        AiModel unsupportedDefault = AiModel.builder()
                .id(41L)
                .name("GPT 5.5")
                .code("gpt-5.5")
                .build();
        AiModel supportedFallback = AiModel.builder()
                .id(42L)
                .name("Seedance")
                .code("seedance-2-0-pro")
                .build();

        when(aiModelService.getDefaultByType(3)).thenReturn(unsupportedDefault);
        when(aiModelService.getListByType(3)).thenReturn(List.of(unsupportedDefault, supportedFallback));
        when(strategyRouter.supports(unsupportedDefault)).thenReturn(false);
        when(strategyRouter.supports(supportedFallback)).thenReturn(true);

        VideoTask completedTask = VideoTask.builder().id(92L).taskId("task-92").status(2).build();
        when(videoGenerationConsumer.submitAndWait(any(VideoTask.class), eq(7200000L))).thenReturn(completedTask);
        when(videoGenerationService.listItems(92L)).thenReturn(List.of(VideoItem.builder()
                .videoUrl("https://example.test/fallback.mp4")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter);

        String result = executor.execute("{\"prompt\":\"镜头缓慢推进\"}",
                ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(7200000L));
        assertThat(taskCaptor.getValue().getModelId()).isEqualTo(42L);
        assertThat(result).contains("fallback.mp4");
    }
}
