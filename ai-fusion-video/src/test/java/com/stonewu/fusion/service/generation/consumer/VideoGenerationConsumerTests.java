package com.stonewu.fusion.service.generation.consumer;

import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.infrastructure.queue.RedisTaskQueue;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategyRouter;
import com.stonewu.fusion.service.storage.MediaStorageService;
import com.stonewu.fusion.service.system.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoGenerationConsumerTests {

    @Test
    void persistVideoItemsStoresEveryPlatformMediaOutput() {
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        MediaStorageService mediaStorageService = mock(MediaStorageService.class);
        VideoTask task = VideoTask.builder().id(10L).build();
        VideoItem item = VideoItem.builder()
                .id(20L)
                .videoUrl("https://provider.example/video.mp4")
                .coverUrl("https://provider.example/cover.png")
                .firstFrameUrl("https://provider.example/first.png")
                .lastFrameUrl("https://provider.example/last.png")
                .build();
        when(videoGenerationService.listItems(10L)).thenReturn(List.of(item));
        when(mediaStorageService.downloadAndStore(any(String.class), any(String.class)))
                .thenAnswer(invocation -> "https://storage.example/" + invocation.getArgument(0, String.class)
                        .substring(invocation.getArgument(0, String.class).lastIndexOf('/') + 1));
        VideoGenerationConsumer consumer = new VideoGenerationConsumer(
                mock(RedisTaskQueue.class), videoGenerationService, mock(AiModelService.class),
                mock(GenerationModelCapabilityService.class), mock(VideoGenerationStrategyRouter.class),
                mediaStorageService, mock(SystemConfigService.class));

        ReflectionTestUtils.invokeMethod(consumer, "persistVideoItems", task);

        assertThat(item.getVideoUrl()).isEqualTo("https://storage.example/video.mp4");
        assertThat(item.getCoverUrl()).isEqualTo("https://storage.example/cover.png");
        assertThat(item.getFirstFrameUrl()).isEqualTo("https://storage.example/first.png");
        assertThat(item.getLastFrameUrl()).isEqualTo("https://storage.example/last.png");
        verify(videoGenerationService).updateItem(item);
    }

    @Test
    void submitTaskUsesConfiguredConcurrencyForGrokVideoModel() {
        RedisTaskQueue taskQueue = mock(RedisTaskQueue.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        AiModelService aiModelService = mock(AiModelService.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);

        AiModel model = AiModel.builder()
                .id(100L)
                .code("grok-image-video")
                .modelType(3)
                .maxConcurrency(5)
                .status(1)
                .build();
        when(aiModelService.getById(100L)).thenReturn(model);
        when(videoGenerationService.create(any(VideoTask.class))).thenAnswer(invocation -> {
            VideoTask created = invocation.getArgument(0);
            created.setId(200L);
            return created;
        });

        VideoGenerationConsumer consumer = new VideoGenerationConsumer(
                taskQueue, videoGenerationService, aiModelService, capabilityService, strategyRouter,
                mock(MediaStorageService.class), systemConfigService);

        consumer.submitTask(VideoTask.builder().modelId(100L).prompt("test prompt").build());

        verify(taskQueue).setMaxConcurrent("video_generation:model:100", 5);
    }

    @Test
        void submitTaskDefaultsWatermarkOffAndAudioOnWhenUnset() {
        RedisTaskQueue taskQueue = mock(RedisTaskQueue.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        AiModelService aiModelService = mock(AiModelService.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);

        AiModel model = AiModel.builder()
                .id(101L)
                .status(1)
                .build();
        when(aiModelService.getById(101L)).thenReturn(model);
        when(videoGenerationService.create(any(VideoTask.class))).thenAnswer(invocation -> {
            VideoTask created = invocation.getArgument(0);
            created.setId(201L);
            return created;
        });

        VideoGenerationConsumer consumer = new VideoGenerationConsumer(
                taskQueue,
                videoGenerationService,
                aiModelService,
                capabilityService,
                strategyRouter,
                mock(MediaStorageService.class),
                systemConfigService
        );

        VideoTask task = VideoTask.builder()
                .modelId(101L)
                .prompt("test prompt")
                .build();

        consumer.submitTask(task);

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationService).create(taskCaptor.capture());
        VideoTask createdTask = taskCaptor.getValue();
        assertThat(createdTask.getWatermark()).isFalse();
        assertThat(createdTask.getGenerateAudio()).isTrue();
    }

    @Test
        void submitTaskPreservesExplicitFlags() {
        RedisTaskQueue taskQueue = mock(RedisTaskQueue.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        AiModelService aiModelService = mock(AiModelService.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);

        AiModel model = AiModel.builder()
                .id(102L)
                .status(1)
                .build();
        when(aiModelService.getById(102L)).thenReturn(model);
        when(videoGenerationService.create(any(VideoTask.class))).thenAnswer(invocation -> {
            VideoTask created = invocation.getArgument(0);
            created.setId(202L);
            return created;
        });

        VideoGenerationConsumer consumer = new VideoGenerationConsumer(
                taskQueue,
                videoGenerationService,
                aiModelService,
                capabilityService,
                strategyRouter,
                mock(MediaStorageService.class),
                systemConfigService
        );

        VideoTask task = VideoTask.builder()
                .modelId(102L)
                .prompt("test prompt")
                .watermark(true)
                .generateAudio(false)
                .build();

        consumer.submitTask(task);

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationService).create(taskCaptor.capture());
        VideoTask createdTask = taskCaptor.getValue();
                assertThat(createdTask.getWatermark()).isTrue();
                assertThat(createdTask.getGenerateAudio()).isFalse();
    }

    @Test
    void submitTaskNormalizesMediaUrlsAndDropsPresetArtStyleReferences() {
        RedisTaskQueue taskQueue = mock(RedisTaskQueue.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        AiModelService aiModelService = mock(AiModelService.class);
        GenerationModelCapabilityService capabilityService = mock(GenerationModelCapabilityService.class);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);

        AiModel model = AiModel.builder()
                .id(103L)
                .status(1)
                .build();
        when(aiModelService.getById(103L)).thenReturn(model);
        when(systemConfigService.resolvePublicUrl("/media/images/first.png"))
                .thenReturn("https://fusion.example.com/media/images/first.png");
        when(systemConfigService.resolvePublicUrl("https://api.example.com/media/images/actor.png"))
                .thenReturn("https://fusion.example.com/media/images/actor.png");
        when(systemConfigService.resolvePublicUrl("https://oss.example.com/ref.png"))
                .thenReturn("https://oss.example.com/ref.png");
        when(videoGenerationService.create(any(VideoTask.class))).thenAnswer(invocation -> {
            VideoTask created = invocation.getArgument(0);
            created.setId(203L);
            return created;
        });

        VideoGenerationConsumer consumer = new VideoGenerationConsumer(
                taskQueue,
                videoGenerationService,
                aiModelService,
                capabilityService,
                strategyRouter,
                mock(MediaStorageService.class),
                systemConfigService
        );

        VideoTask task = VideoTask.builder()
                .modelId(103L)
                .prompt("test prompt")
                .firstFrameImageUrl("/media/images/first.png")
                .referenceImageUrls("""
                        [
                          "/api/art-styles/realistic.jpg",
                          "https://api.example.com/media/images/actor.png",
                          "https://oss.example.com/ref.png"
                        ]
                        """)
                .build();

        consumer.submitTask(task);

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationService).create(taskCaptor.capture());
        VideoTask createdTask = taskCaptor.getValue();
        assertThat(createdTask.getFirstFrameImageUrl()).isEqualTo("https://fusion.example.com/media/images/first.png");
        assertThat(createdTask.getReferenceImageUrls()).contains("https://fusion.example.com/media/images/actor.png");
        assertThat(createdTask.getReferenceImageUrls()).contains("https://oss.example.com/ref.png");
        assertThat(createdTask.getReferenceImageUrls()).doesNotContain("realistic.jpg");
    }
}
