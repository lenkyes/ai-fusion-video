package com.stonewu.fusion.service.generation.strategy;

import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.model.AiModelMetadataResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoGenerationStrategyRouterTests {

    @Test
    void shouldRouteByApiPlatformInsteadOfModelCode() {
        ApiConfigService apiConfigService = mock(ApiConfigService.class);
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(apiConfigService);

        VideoGenerationStrategy newApiStrategy = mock(VideoGenerationStrategy.class);
        when(newApiStrategy.getName()).thenReturn("newapi");

        VideoGenerationStrategy flowStrategy = mock(VideoGenerationStrategy.class);
        when(flowStrategy.getName()).thenReturn("GoogleFlowReverseApi");

        VideoGenerationStrategyRouter router = new VideoGenerationStrategyRouter(
                List.of(flowStrategy, newApiStrategy),
                resolver
        );

        AiModel model = AiModel.builder()
                .id(1L)
                .code("kling-v1")
                .apiConfigId(11L)
                .modelType(3)
                .build();
        when(apiConfigService.getById(11L)).thenReturn(ApiConfig.builder().id(11L).platform("newapi").build());

        assertSame(newApiStrategy, router.resolve(model));
    }

    @Test
    void shouldReportUnsupportedOpenAiCompatibleVideoPlatform() {
        ApiConfigService apiConfigService = mock(ApiConfigService.class);
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(apiConfigService);

        VideoGenerationStrategy newApiStrategy = mock(VideoGenerationStrategy.class);
        when(newApiStrategy.getName()).thenReturn("newapi");

        VideoGenerationStrategyRouter router = new VideoGenerationStrategyRouter(
                List.of(newApiStrategy),
                resolver
        );

        AiModel openAiCompatibleVideoModel = AiModel.builder()
                .id(2L)
                .code("gpt-5.5")
                .apiConfigId(12L)
                .modelType(3)
                .build();
        AiModel newApiVideoModel = AiModel.builder()
                .id(3L)
                .code("kling-v1")
                .apiConfigId(13L)
                .modelType(3)
                .build();
        when(apiConfigService.getById(12L)).thenReturn(ApiConfig.builder().id(12L).platform("openai_compatible").build());
        when(apiConfigService.getById(13L)).thenReturn(ApiConfig.builder().id(13L).platform("newapi").build());

        assertFalse(router.supports(openAiCompatibleVideoModel));
        assertTrue(router.supports(newApiVideoModel));
    }

    @Test
    void shouldRouteOpenAiCompatibleSeedanceVideoThroughNewApiStrategy() {
        ApiConfigService apiConfigService = mock(ApiConfigService.class);
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(apiConfigService);

        VideoGenerationStrategy newApiStrategy = mock(VideoGenerationStrategy.class);
        when(newApiStrategy.getName()).thenReturn("newapi");

        VideoGenerationStrategyRouter router = new VideoGenerationStrategyRouter(
                List.of(newApiStrategy),
                resolver
        );

        AiModel seedanceVideoModel = AiModel.builder()
                .id(4L)
                .code("bytedance/seedance-2-fast")
                .apiConfigId(14L)
                .modelType(3)
                .build();
        when(apiConfigService.getById(14L)).thenReturn(ApiConfig.builder()
                .id(14L)
                .platform("openai_compatible")
                .build());

        assertTrue(router.supports(seedanceVideoModel));
        assertSame(newApiStrategy, router.resolve(seedanceVideoModel));
    }

    @Test
    void shouldRouteOpenAiCompatibleGrokImagineVideoThroughNewApiStrategy() {
        ApiConfigService apiConfigService = mock(ApiConfigService.class);
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(apiConfigService);
        VideoGenerationStrategy newApiStrategy = mock(VideoGenerationStrategy.class);
        when(newApiStrategy.getName()).thenReturn("newapi");
        VideoGenerationStrategyRouter router = new VideoGenerationStrategyRouter(List.of(newApiStrategy), resolver);

        AiModel model = AiModel.builder().id(6L)
                .code("grok-imagine-video-1-5-preview")
                .apiConfigId(16L).modelType(3).build();
        when(apiConfigService.getById(16L)).thenReturn(ApiConfig.builder()
                .id(16L).platform("openai_compatible").build());

        assertTrue(router.supports(model));
        assertSame(newApiStrategy, router.resolve(model));
    }

    @Test
    void shouldRouteOpenAiCompatibleGrokImageVideoAliasThroughNewApiStrategy() {
        ApiConfigService apiConfigService = mock(ApiConfigService.class);
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(apiConfigService);
        VideoGenerationStrategy newApiStrategy = mock(VideoGenerationStrategy.class);
        when(newApiStrategy.getName()).thenReturn("newapi");
        VideoGenerationStrategyRouter router = new VideoGenerationStrategyRouter(List.of(newApiStrategy), resolver);

        AiModel model = AiModel.builder().id(7L)
                .code("grok-image-video")
                .apiConfigId(17L).modelType(3).build();
        when(apiConfigService.getById(17L)).thenReturn(ApiConfig.builder()
                .id(17L).platform("openai_compatible").build());

        assertTrue(router.supports(model));
        assertSame(newApiStrategy, router.resolve(model));
    }

    @Test
    void shouldRouteExplicitConfiguredVideoStrategy() {
        ApiConfigService apiConfigService = mock(ApiConfigService.class);
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(apiConfigService);

        VideoGenerationStrategy newApiStrategy = mock(VideoGenerationStrategy.class);
        when(newApiStrategy.getName()).thenReturn("newapi");

        VideoGenerationStrategyRouter router = new VideoGenerationStrategyRouter(
                List.of(newApiStrategy),
                resolver
        );

        AiModel customVideoModel = AiModel.builder()
                .id(5L)
                .code("custom-video-model")
                .apiConfigId(15L)
                .modelType(3)
                .config("{\"videoStrategy\":\"newapi\"}")
                .build();
        when(apiConfigService.getById(15L)).thenReturn(ApiConfig.builder()
                .id(15L)
                .platform("openai_compatible")
                .build());

        assertTrue(router.supports(customVideoModel));
        assertSame(newApiStrategy, router.resolve(customVideoModel));
    }
}
