package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ModelPresetService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.model.AiModelMetadataResolver;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategyRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetGenerationModelCapabilitiesToolExecutorTests {

    @Test
    void shouldReturnImageAndVideoCapabilitiesForCurrentDefaults() {
        AiModelService aiModelService = mock(AiModelService.class);
        ApiConfigService apiConfigService = mock(ApiConfigService.class);
        GenerationModelCapabilityService capabilityService = new GenerationModelCapabilityService(
                new AiModelMetadataResolver(apiConfigService), new ModelPresetService() {
            @Override
            public String getPresetConfig(String code) {
                return switch (code) {
                    case "gpt-image-1" -> """
                            {
                              "supportReferenceImages": false,
                              "minReferenceImages": 0,
                              "maxReferenceImages": 0,
                              "defaultWidth": 1024,
                              "defaultHeight": 1024
                            }
                            """;
                    case "veo_3_1_r2v_fast" -> """
                            {
                              "supportFirstFrame": false,
                              "supportLastFrame": false,
                              "supportReferenceImages": true,
                              "supportReferenceVideos": false,
                              "supportReferenceAudios": false,
                              "minImageInputs": 0,
                              "maxImageInputs": 3,
                              "maxReferenceImages": 3,
                              "supportedAspectRatios": ["16:9", "9:16"]
                            }
                            """;
                    default -> null;
                };
            }
        });

        AiModel imageModel = AiModel.builder().id(11L).apiConfigId(101L).name("GPT Image 1").code("gpt-image-1").build();
        AiModel videoModel = AiModel.builder().id(22L).apiConfigId(102L).modelType(3).name("Veo R2V").code("veo_3_1_r2v_fast").build();
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);

        when(apiConfigService.getById(101L)).thenReturn(ApiConfig.builder().id(101L).platform("openai_compatible").build());
        when(apiConfigService.getById(102L)).thenReturn(ApiConfig.builder().id(102L).platform("GoogleFlowReverseApi").build());
        when(aiModelService.getDefaultByType(2)).thenReturn(imageModel);
        when(aiModelService.getDefaultByType(3)).thenReturn(videoModel);
        when(strategyRouter.supports(videoModel)).thenReturn(true);

        GetGenerationModelCapabilitiesToolExecutor executor =
                new GetGenerationModelCapabilitiesToolExecutor(aiModelService, capabilityService, strategyRouter);

        String result = executor.execute("{}", ToolExecutionContext.builder().userId(1L).build());
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("success", json.getStr("status"));
        assertFalse(json.getJSONObject("image").getBool("supportsReferenceImages"));
        assertTrue(json.getJSONObject("video").getBool("supportsReferenceImages"));
        assertEquals("default_model", json.getJSONObject("image").getStr("selectionSource"));
        assertTrue(json.getJSONObject("video").getStr("toolGuidance").contains("referenceImageUrls"));
    }

    @Test
    void shouldSupportSingleModelTypeQuery() {
        AiModelService aiModelService = mock(AiModelService.class);
        ApiConfigService apiConfigService = mock(ApiConfigService.class);
        GenerationModelCapabilityService capabilityService = new GenerationModelCapabilityService(
                new AiModelMetadataResolver(apiConfigService), new ModelPresetService());

        AiModel imageModel = AiModel.builder().id(11L).apiConfigId(101L).name("GPT Image 1").code("gpt-image-1")
                .config("{\"supportReferenceImages\":false,\"maxReferenceImages\":0}")
                .build();
        when(apiConfigService.getById(101L)).thenReturn(ApiConfig.builder().id(101L).platform("openai_compatible").build());
        when(aiModelService.getDefaultByType(2)).thenReturn(imageModel);
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);

        GetGenerationModelCapabilitiesToolExecutor executor =
                new GetGenerationModelCapabilitiesToolExecutor(aiModelService, capabilityService, strategyRouter);

        String result = executor.execute("{\"modelType\":\"image\"}", ToolExecutionContext.builder().userId(1L).build());
        JSONObject json = JSONUtil.parseObj(result);

        assertEquals("image", json.getStr("requestedModelType"));
        assertTrue(json.containsKey("image"));
        assertFalse(json.containsKey("video"));
    }

    @Test
    void shouldWarnWhenDefaultVideoModelHasNoGenerationStrategy() {
        AiModelService aiModelService = mock(AiModelService.class);
        ApiConfigService apiConfigService = mock(ApiConfigService.class);
        GenerationModelCapabilityService capabilityService = new GenerationModelCapabilityService(
                new AiModelMetadataResolver(apiConfigService), new ModelPresetService());
        VideoGenerationStrategyRouter strategyRouter = mock(VideoGenerationStrategyRouter.class);

        AiModel gptVideoModel = AiModel.builder()
                .id(33L)
                .apiConfigId(303L)
                .modelType(3)
                .name("GPT 5.5")
                .code("gpt-5.5")
                .build();
        when(apiConfigService.getById(303L)).thenReturn(ApiConfig.builder()
                .id(303L)
                .platform("openai_compatible")
                .build());
        when(aiModelService.getDefaultByType(3)).thenReturn(gptVideoModel);
        when(strategyRouter.supports(gptVideoModel)).thenReturn(false);

        GetGenerationModelCapabilitiesToolExecutor executor =
                new GetGenerationModelCapabilitiesToolExecutor(aiModelService, capabilityService, strategyRouter);

        String result = executor.execute("{\"modelType\":\"video\"}", ToolExecutionContext.builder().userId(1L).build());
        JSONObject json = JSONUtil.parseObj(result).getJSONObject("video");

        assertFalse(json.getBool("strategySupported"));
        assertEquals("unsupported_default_model", json.getStr("selectionSource"));
        assertTrue(json.getStr("toolGuidance").contains("请不要调用 generate_video"));
    }
}
