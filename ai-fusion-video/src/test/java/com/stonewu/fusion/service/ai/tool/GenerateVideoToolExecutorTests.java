package com.stonewu.fusion.service.ai.tool;

import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.generation.consumer.VideoGenerationConsumer;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategyRouter;
import com.stonewu.fusion.service.storyboard.StoryboardService;
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
        StoryboardService storyboardService = mock(StoryboardService.class);

        AiModel model = AiModel.builder()
                .id(31L)
                .status(1)
                .code("seedance")
                .build();
        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);
        when(storyboardService.getItemById(3307L)).thenReturn(StoryboardItem.builder()
                .id(3307L)
                .storyboardId(220L)
                .build());
        when(storyboardService.getById(220L)).thenReturn(Storyboard.builder()
                .id(220L)
                .projectId(100L)
                .build());

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
                systemConfigService,
                storyboardService);
        ReflectionTestUtils.setField(executor, "waitTimeoutMs", 12345L);

        String result = executor.execute("{\"prompt\":\"镜头缓慢推进\",\"duration\":5,\"storyboardItemId\":3307}",
                ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(12345L));
        assertThat(taskCaptor.getValue().getModelId()).isEqualTo(31L);
        assertThat(taskCaptor.getValue().getProjectId()).isEqualTo(100L);
        assertThat(taskCaptor.getValue().getCategory()).isEqualTo("storyboard_item:3307");
        assertThat(taskCaptor.getValue().getGenerateAudio()).isTrue();
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
        StoryboardService storyboardService = mock(StoryboardService.class);

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
                systemConfigService,
                storyboardService);

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
        StoryboardService storyboardService = mock(StoryboardService.class);

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
        when(videoGenerationService.findLatestByCategoryFamily("storyboard_item:3308", 7L, 31L)).thenReturn(existingTask);
        when(videoGenerationService.listItems(88L)).thenReturn(List.of(VideoItem.builder()
                .platformTaskId("remote-task-1")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter,
                systemConfigService,
                storyboardService);

        String result = executor.execute("{\"prompt\":\"镜头缓慢推进\",\"storyboardItemId\":3308}",
                ToolExecutionContext.builder().userId(7L).build());

        verify(videoGenerationConsumer, never()).submitAndWait(any(VideoTask.class), anyLong());
        assertThat(result).contains("\"status\":\"error\"");
        assertThat(result).contains("\"retryable\":false");
        assertThat(result).contains("\"remoteTaskSubmitted\":true");
        assertThat(result).contains("remote-task-1");
    }

    @Test
    void shouldCreateNewTaskForFailedStoryboardTaskWhenForceRegenerateIsTrue() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        StoryboardService storyboardService = mock(StoryboardService.class);

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
        VideoTask completedTask = VideoTask.builder().id(95L).taskId("task-95").status(2).build();

        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);
        when(videoGenerationService.findLatestByCategoryFamily("storyboard_item:3308", 7L, 31L)).thenReturn(existingTask);
        when(videoGenerationConsumer.submitAndWait(any(VideoTask.class), eq(7200000L))).thenReturn(completedTask);
        when(videoGenerationService.listItems(95L)).thenReturn(List.of(VideoItem.builder()
                .videoUrl("https://example.test/regenerated.mp4")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter,
                systemConfigService,
                storyboardService);

        String result = executor.execute("""
                {
                  "prompt": "镜头缓慢推进",
                  "storyboardItemId": 3308,
                  "forceRegenerate": true,
                  "generationRequestId": "request-20260529"
                }
                """, ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(7200000L));
        assertThat(taskCaptor.getValue().getCategory())
                .isEqualTo("storyboard_item:3308:request:req_5bfc536a59099c4f");
        assertThat(result).contains("\"status\":\"success\"");
        assertThat(result).contains("regenerated.mp4");
    }

    @Test
    void shouldNotCreateSecondRemoteTaskForSameRegenerationRequest() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        StoryboardService storyboardService = mock(StoryboardService.class);

        AiModel model = AiModel.builder()
                .id(31L)
                .status(1)
                .code("seedance")
                .build();
        VideoTask existingTask = VideoTask.builder()
                .id(96L)
                .taskId("task-96")
                .status(3)
                .category("storyboard_item:3308:request:req_5bfc536a59099c4f")
                .build();

        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);
        when(videoGenerationService.findLatestByCategoryFamily("storyboard_item:3308", 7L, 31L))
                .thenReturn(existingTask);
        when(videoGenerationService.listItems(96L)).thenReturn(List.of(VideoItem.builder()
                .platformTaskId("remote-task-96")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter,
                systemConfigService,
                storyboardService);

        String result = executor.execute("""
                {
                  "prompt": "镜头缓慢推进",
                  "storyboardItemId": 3308,
                  "forceRegenerate": true,
                  "generationRequestId": "request-20260529"
                }
                """, ToolExecutionContext.builder().userId(7L).build());

        verify(videoGenerationConsumer, never()).submitAndWait(any(VideoTask.class), anyLong());
        assertThat(result).contains("\"status\":\"error\"");
        assertThat(result).contains("\"retryable\":false");
        assertThat(result).contains("\"remoteTaskSubmitted\":true");
        assertThat(result).contains("remote-task-96");
    }

    @Test
    void shouldCreateNewTaskForCompletedStoryboardTaskWhenForceRegenerateHasNewRequestId() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        StoryboardService storyboardService = mock(StoryboardService.class);

        AiModel model = AiModel.builder()
                .id(31L)
                .status(1)
                .code("seedance")
                .build();
        VideoTask existingTask = VideoTask.builder()
                .id(97L)
                .taskId("task-97")
                .status(2)
                .category("storyboard_item:3308:request:old-request")
                .build();
        VideoTask completedTask = VideoTask.builder().id(98L).taskId("task-98").status(2).build();

        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);
        when(videoGenerationService.findLatestByCategoryFamily("storyboard_item:3308", 7L, 31L))
                .thenReturn(existingTask);
        when(videoGenerationConsumer.submitAndWait(any(VideoTask.class), eq(7200000L))).thenReturn(completedTask);
        when(videoGenerationService.listItems(98L)).thenReturn(List.of(VideoItem.builder()
                .videoUrl("https://example.test/new-regenerated.mp4")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter,
                systemConfigService,
                storyboardService);

        String result = executor.execute("""
                {
                  "prompt": "镜头缓慢推进",
                  "storyboardItemId": 3308,
                  "forceRegenerate": true,
                  "generationRequestId": "new-request"
                }
                """, ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(7200000L));
        assertThat(taskCaptor.getValue().getCategory()).isEqualTo("storyboard_item:3308:request:req_b22853fd971ddbf4");
        assertThat(result).contains("\"status\":\"success\"");
        assertThat(result).contains("new-regenerated.mp4");
    }

    @Test
    void shouldKeepRegenerationCategoryWithinDatabaseColumnLimit() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        StoryboardService storyboardService = mock(StoryboardService.class);

        AiModel model = AiModel.builder()
                .id(31L)
                .status(1)
                .code("seedance")
                .build();
        VideoTask completedTask = VideoTask.builder().id(101L).taskId("task-101").status(2).build();

        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);
        when(videoGenerationConsumer.submitAndWait(any(VideoTask.class), eq(7200000L))).thenReturn(completedTask);
        when(videoGenerationService.listItems(101L)).thenReturn(List.of(VideoItem.builder()
                .videoUrl("https://example.test/max-category.mp4")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter,
                systemConfigService,
                storyboardService);

        String result = executor.execute("""
                {
                  "prompt": "镜头缓慢推进",
                  "storyboardItemId": 9223372036854775807,
                  "forceRegenerate": true,
                  "generationRequestId": "storyboard-video-1770000000000-this-is-a-very-long-request-id"
                }
                """, ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(7200000L));
        assertThat(taskCaptor.getValue().getCategory())
                .startsWith("storyboard_item:9223372036854775807:request:req_");
        assertThat(taskCaptor.getValue().getCategory().length()).isLessThanOrEqualTo(64);
        assertThat(result).contains("\"status\":\"success\"");
    }

    @Test
    void shouldWaitForActiveStoryboardTaskEvenWhenForceRegenerateIsTrue() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        StoryboardService storyboardService = mock(StoryboardService.class);

        AiModel model = AiModel.builder()
                .id(31L)
                .status(1)
                .code("seedance")
                .build();
        VideoTask activeTask = VideoTask.builder()
                .id(99L)
                .taskId("task-active")
                .status(1)
                .category("storyboard_item:3308:request:running-request")
                .build();
        VideoTask completedTask = VideoTask.builder().id(100L).taskId("task-active").status(2).build();

        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);
        when(videoGenerationService.findLatestActiveByCategoryFamily("storyboard_item:3308", 7L, 31L))
                .thenReturn(activeTask);
        when(videoGenerationConsumer.waitForTask("task-active", 7200000L)).thenReturn(completedTask);
        when(videoGenerationService.listItems(100L)).thenReturn(List.of(VideoItem.builder()
                .videoUrl("https://example.test/active-result.mp4")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter,
                systemConfigService,
                storyboardService);

        String result = executor.execute("""
                {
                  "prompt": "镜头缓慢推进",
                  "storyboardItemId": 3308,
                  "forceRegenerate": true,
                  "generationRequestId": "new-request"
                }
                """, ToolExecutionContext.builder().userId(7L).build());

        verify(videoGenerationConsumer, never()).submitAndWait(any(VideoTask.class), anyLong());
        assertThat(result).contains("\"status\":\"success\"");
        assertThat(result).contains("active-result.mp4");
    }

    @Test
    void shouldReturnNonRetryableWhenInitialStoryboardTaskFailsAfterRemoteSubmit() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        StoryboardService storyboardService = mock(StoryboardService.class);

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
                systemConfigService,
                storyboardService);

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
        StoryboardService storyboardService = mock(StoryboardService.class);

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
                systemConfigService,
                storyboardService);

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

    @Test
    void shouldAutoFillStoryboardFirstAndLastFrameWhenAgentOmitsThem() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        StoryboardService storyboardService = mock(StoryboardService.class);

        AiModel model = AiModel.builder()
                .id(31L)
                .status(1)
                .code("bytedance/seedance-2-fast")
                .build();
        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);
        when(capabilityService.resolveVideoCapability(model)).thenReturn(
                new GenerationModelCapabilityService.VideoModelCapability(
                        true, true, true, false, false, 0, null, 9, 0, 0));
        when(storyboardService.getItemById(3310L)).thenReturn(StoryboardItem.builder()
                .id(3310L)
                .generatedImageUrl("/media/images/shot-start.png")
                .customData("{\"lastFrameImageUrl\":\"/media/images/shot-end.png\"}")
                .build());
        when(systemConfigService.resolvePublicUrl("/media/images/shot-start.png"))
                .thenReturn("https://fusion.example.com/media/images/shot-start.png");
        when(systemConfigService.resolvePublicUrl("/media/images/shot-end.png"))
                .thenReturn("https://fusion.example.com/media/images/shot-end.png");

        VideoTask completedTask = VideoTask.builder().id(94L).taskId("task-94").status(2).build();
        when(videoGenerationConsumer.submitAndWait(any(VideoTask.class), eq(7200000L))).thenReturn(completedTask);
        when(videoGenerationService.listItems(94L)).thenReturn(List.of(VideoItem.builder()
                .videoUrl("https://example.test/video.mp4")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService,
                videoGenerationService,
                videoGenerationConsumer,
                capabilityService,
                strategyRouter,
                systemConfigService,
                storyboardService);

        String result = executor.execute("""
                {
                  "prompt": "镜头缓慢推进",
                  "storyboardItemId": 3310
                }
                """, ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(7200000L));
        VideoTask task = taskCaptor.getValue();

        assertThat(task.getFirstFrameImageUrl()).isEqualTo("https://fusion.example.com/media/images/shot-start.png");
        assertThat(task.getLastFrameImageUrl()).isEqualTo("https://fusion.example.com/media/images/shot-end.png");
        assertThat(task.getGenerateMode()).isEqualTo("image2video");
        assertThat(result).contains("\"status\":\"success\"");
    }

    @Test
    void shouldUsePreviousShotReferencesAndPreservePreGeneratedCurrentTailFrame() throws Exception {
        AiModelService aiModelService = mock(AiModelService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        VideoGenerationConsumer videoGenerationConsumer = mock(VideoGenerationConsumer.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        StoryboardService storyboardService = mock(StoryboardService.class);

        AiModel model = AiModel.builder().id(31L).code("seedance-2").build();
        StoryboardItem previous = StoryboardItem.builder()
                .id(3309L)
                .storyboardId(220L)
                .generatedVideoUrl("/media/videos/previous.mp4")
                .customData("{\"lastFrameImageUrl\":\"/media/images/previous-tail.png\"}")
                .build();
        StoryboardItem current = StoryboardItem.builder()
                .id(3310L)
                .storyboardId(220L)
                .customData("{\"lastFrameImageUrl\":\"/media/images/pre-generated-tail.png\"}")
                .build();
        when(aiModelService.getDefaultByType(3)).thenReturn(model);
        when(strategyRouter.supports(model)).thenReturn(true);
        when(capabilityService.resolveVideoCapability(model)).thenReturn(
                new GenerationModelCapabilityService.VideoModelCapability(
                        true, true, true, true, false, 0, null, 9, 1, 0));
        when(storyboardService.getItemById(3310L)).thenReturn(current);
        when(storyboardService.listItems(220L)).thenReturn(List.of(previous, current));
        when(systemConfigService.resolvePublicUrl("/media/images/pre-generated-tail.png"))
                .thenReturn("https://fusion.test/media/images/pre-generated-tail.png");
        when(systemConfigService.resolvePublicUrl("/media/images/previous-tail.png"))
                .thenReturn("https://fusion.test/media/images/previous-tail.png");
        when(systemConfigService.resolvePublicUrl("/media/videos/previous.mp4"))
                .thenReturn("https://fusion.test/media/videos/previous.mp4");

        VideoTask completed = VideoTask.builder().id(102L).status(2).build();
        when(videoGenerationConsumer.submitAndWait(any(VideoTask.class), eq(7200000L))).thenReturn(completed);
        when(videoGenerationService.listItems(102L)).thenReturn(List.of(VideoItem.builder()
                .videoUrl("https://fusion.test/media/videos/current.mp4")
                .lastFrameUrl("https://fusion.test/media/images/current-tail.png")
                .build()));

        GenerateVideoToolExecutor executor = new GenerateVideoToolExecutor(
                aiModelService, videoGenerationService, videoGenerationConsumer,
                capabilityService, strategyRouter, systemConfigService, storyboardService);

        executor.execute("{\"prompt\":\"continue the shot\",\"storyboardItemId\":3310}",
                ToolExecutionContext.builder().userId(7L).build());

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationConsumer).submitAndWait(taskCaptor.capture(), eq(7200000L));
        assertThat(taskCaptor.getValue().getFirstFrameImageUrl())
                .isEqualTo("https://fusion.test/media/images/previous-tail.png");
        assertThat(taskCaptor.getValue().getLastFrameImageUrl())
                .isEqualTo("https://fusion.test/media/images/pre-generated-tail.png");
        assertThat(taskCaptor.getValue().getReferenceVideoUrls())
                .contains("https://fusion.test/media/videos/previous.mp4");
        verify(storyboardService).updateItem(current);
        assertThat(current.getGeneratedVideoUrl()).isEqualTo("https://fusion.test/media/videos/current.mp4");
        assertThat(current.getCustomData())
                .isEqualTo("{\"lastFrameImageUrl\":\"/media/images/pre-generated-tail.png\"}");
    }
}
