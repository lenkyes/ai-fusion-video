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
import com.stonewu.fusion.service.system.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        SystemConfigService systemConfigService = mock(SystemConfigService.class);

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
                strategyRouter,
                systemConfigService);
        ReflectionTestUtils.setField(executor, "waitTimeoutMs", 12345L);

        String result = executor.execute("{\"prompt\":\"镜头缓慢推进\",\"duration\":5,\"storyboardItemId\":3307}",
                ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(12345L));
        assertThat(taskCaptor.getValue().getModelId()).isEqualTo(31L);
        assertThat(taskCaptor.getValue().getCategory()).isEqualTo("storyboard_item:3307");
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
        SystemConfigService systemConfigService = mock(SystemConfigService.class);

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
                strategyRouter,
                systemConfigService);

        String result = executor.execute("{\"prompt\":\"镜头缓慢推进\"}",
                ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(7200000L));
        assertThat(taskCaptor.getValue().getModelId()).isEqualTo(42L);
        assertThat(result).contains("fallback.mp4");
    }

    @Test
    void shouldNotCreateNewRemoteTaskWhenStoryboardVideoTaskAlreadyFailed() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);

        AiModel model = AiModel.builder()
                .id(31L)
                .status(1)
                .code("seedance")
                .build();
        VideoTask existingTask = VideoTask.builder()
                .id(88L)
                .taskId("task-88")
                .status(3)
                .category("storyboard_item:3308")
                .build();

        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);
        when(videoGenerationService.findLatestByCategory("storyboard_item:3308", 7L, 31L)).thenReturn(existingTask);
        when(videoGenerationService.listItems(88L)).thenReturn(List.of(VideoItem.builder()
                .platformTaskId("remote-task-1")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter,
                systemConfigService);

        String result = executor.execute("{\"prompt\":\"镜头缓慢推进\",\"storyboardItemId\":3308}",
                ToolExecutionContext.builder().userId(7L).build());

        verify(videoGenerationConsumer, never()).submitAndWait(any(VideoTask.class), anyLong());
        assertThat(result).contains("\"status\":\"error\"");
        assertThat(result).contains("\"retryable\":false");
        assertThat(result).contains("\"remoteTaskSubmitted\":true");
        assertThat(result).contains("remote-task-1");
    }

    @Test
    void shouldReturnNonRetryableWhenInitialStoryboardTaskFailsAfterRemoteSubmit() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);

        AiModel model = AiModel.builder()
                .id(31L)
                .status(1)
                .code("seedance")
                .build();

        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);
        when(videoGenerationConsumer.submitAndWait(any(VideoTask.class), eq(7200000L))).thenAnswer(invocation -> {
            VideoTask submitted = invocation.getArgument(0);
            submitted.setId(89L);
            submitted.setTaskId("local-task-89");
            throw new RuntimeException("New API 返回成功但无视频 URL: remote-task-2");
        });
        when(videoGenerationService.listItems(89L)).thenReturn(List.of(VideoItem.builder()
                .platformTaskId("remote-task-2")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter,
                systemConfigService);

        String result = executor.execute("{\"prompt\":\"镜头缓慢推进\",\"storyboardItemId\":3308}",
                ToolExecutionContext.builder().userId(7L).build());

        verify(videoGenerationConsumer).submitAndWait(any(VideoTask.class), eq(7200000L));
        assertThat(result).contains("\"status\":\"error\"");
        assertThat(result).contains("\"retryable\":false");
        assertThat(result).contains("\"remoteTaskSubmitted\":true");
        assertThat(result).contains("local-task-89");
        assertThat(result).contains("remote-task-2");
    }

    @Test
    void shouldResolveRelativeAssetUrlsAndSkipPresetArtStyleReferenceForVideo() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);

        AiModel model = AiModel.builder()
                .id(31L)
                .status(1)
                .code("seedance")
                .build();
        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);
        when(systemConfigService.resolvePublicUrl("/media/images/first.png"))
                .thenReturn("https://fusion.example.com/media/images/first.png");
        when(systemConfigService.resolvePublicUrl("/media/images/actor.png"))
                .thenReturn("https://fusion.example.com/media/images/actor.png");
        when(systemConfigService.resolvePublicUrl("https://oss.example.com/ref.png"))
                .thenReturn("https://oss.example.com/ref.png");

        VideoTask completedTask = VideoTask.builder().id(93L).taskId("task-93").status(2).build();
        when(videoGenerationConsumer.submitAndWait(any(VideoTask.class), eq(7200000L))).thenReturn(completedTask);
        when(videoGenerationService.listItems(93L)).thenReturn(List.of(VideoItem.builder()
                .videoUrl("https://example.test/video.mp4")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter,
                systemConfigService);

        String result = executor.execute("""
                {
                  "prompt": "镜头缓慢推进",
                  "firstFrameImageUrl": "/media/images/first.png",
                  "referenceImageUrls": [
                    "/api/art-styles/realistic.jpg",
                    "/media/images/actor.png",
                    "https://oss.example.com/ref.png"
                  ],
                  "storyboardItemId": 3309
                }
                """, ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(7200000L));
        VideoTask task = taskCaptor.getValue();

        assertThat(task.getFirstFrameImageUrl()).isEqualTo("https://fusion.example.com/media/images/first.png");
        assertThat(task.getReferenceImageUrls()).contains("https://fusion.example.com/media/images/actor.png");
        assertThat(task.getReferenceImageUrls()).contains("https://oss.example.com/ref.png");
        assertThat(task.getReferenceImageUrls()).doesNotContain("realistic.jpg");
        assertThat(result).contains("\"status\":\"success\"");
    }
}
