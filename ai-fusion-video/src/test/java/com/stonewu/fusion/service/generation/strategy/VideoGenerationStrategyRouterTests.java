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
}
