package com.stonewu.fusion.service.ai.model;

import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.service.ai.ApiConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AiModelMetadataResolverTests {

    @Test
    void shouldInferGrokImagineVideoFamilyAndProtocol() {
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(mock(ApiConfigService.class));
        AiModel model = AiModel.builder()
                .name("Grok Imagine Video 1.5 Preview")
                .code("grok-imagine-video-1-5-preview")
                .modelType(3)
                .build();

        AiModelMetadata metadata = resolver.resolve(model, "openai_compatible");

        assertEquals("grok_imagine", metadata.modelFamily());
        assertEquals("grok_imagine", metadata.modelProtocol());
    }

    @Test
    void shouldInferNewApiSpecificProtocolFromFamilyKeywords() {
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(mock(ApiConfigService.class));
        AiModel model = AiModel.builder()
                .name("即梦 Video")
                .code("jimeng-v1")
                .modelType(3)
                .build();

        AiModelMetadata metadata = resolver.resolve(model, "newapi");

        assertEquals("jimeng", metadata.modelFamily());
        assertEquals("jimeng", metadata.modelProtocol());
    }

    @Test
    void shouldFallbackToGenericProtocolForNewApiGenericVideoModel() {
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(mock(ApiConfigService.class));
        AiModel model = AiModel.builder()
                .name("Generic Video")
                .code("video-model-v1")
                .modelType(3)
                .build();

        AiModelMetadata metadata = resolver.resolve(model, "newapi");

        assertEquals("generic", metadata.modelFamily());
        assertEquals("generic", metadata.modelProtocol());
    }

    @Test
    void shouldInferSeedanceProtocolForNewApiSeedanceModel() {
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(mock(ApiConfigService.class));
        AiModel model = AiModel.builder()
                .name("Seedance 2 Fast")
                .code("bytedance/seedance-2-fast")
                .modelType(3)
                .build();

        AiModelMetadata metadata = resolver.resolve(model, "newapi");

        assertEquals("seedance", metadata.modelFamily());
        assertEquals("seedance", metadata.modelProtocol());
    }

    @Test
    void shouldTreatExplicitGenericMetadataAsWeakWhenCodeHasSpecificFamily() {
        AiModelMetadataResolver resolver = new AiModelMetadataResolver(mock(ApiConfigService.class));
        AiModel model = AiModel.builder()
                .name("Seedance 2 Fast")
                .code("bytedance/seedance-2-fast")
                .modelType(3)
                .modelFamily("generic")
                .modelProtocol("generic")
                .build();

        AiModelMetadata metadata = resolver.resolve(model, "newapi");

        assertEquals("seedance", metadata.modelFamily());
        assertEquals("seedance", metadata.modelProtocol());
    }
}
