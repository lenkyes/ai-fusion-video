package com.stonewu.fusion.service.generation.strategy.impl.newapi;

import cn.hutool.json.JSONObject;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.service.ai.model.AiModelMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewApiVideoProtocolRouterTests {

    @Test
    void shouldResolveDedicatedProtocolAdapterWhenPresent() {
        NewApiVideoProtocolAdapter genericAdapter = mock(NewApiVideoProtocolAdapter.class);
        when(genericAdapter.getProtocol()).thenReturn("generic");
        NewApiVideoProtocolAdapter jimengAdapter = mock(NewApiVideoProtocolAdapter.class);
        when(jimengAdapter.getProtocol()).thenReturn("jimeng");

        NewApiVideoProtocolRouter router = new NewApiVideoProtocolRouter(List.of(genericAdapter, jimengAdapter));
        NewApiVideoProtocolContext context = new NewApiVideoProtocolContext(
                AiModel.builder().code("jimeng-v1").build(),
                null,
                null,
                new JSONObject(),
                new AiModelMetadata("newapi", "newapi", "jimeng", "jimeng")
        );

        assertSame(jimengAdapter, router.resolve(context));
    }

    @Test
    void shouldFallbackToGenericAdapterWhenDedicatedProtocolMissing() {
        NewApiVideoProtocolAdapter genericAdapter = mock(NewApiVideoProtocolAdapter.class);
        when(genericAdapter.getProtocol()).thenReturn("generic");

        NewApiVideoProtocolRouter router = new NewApiVideoProtocolRouter(List.of(genericAdapter));
        NewApiVideoProtocolContext context = new NewApiVideoProtocolContext(
                AiModel.builder().code("kling-v1").build(),
                null,
                null,
                new JSONObject(),
                new AiModelMetadata("newapi", "newapi", "kling", "kling")
        );

        assertSame(genericAdapter, router.resolve(context));
    }

    @Test
    void shouldPreferFamilyAdapterWhenStoredProtocolIsGeneric() {
        NewApiVideoProtocolAdapter genericAdapter = mock(NewApiVideoProtocolAdapter.class);
        when(genericAdapter.getProtocol()).thenReturn("generic");
        NewApiVideoProtocolAdapter seedanceAdapter = mock(NewApiVideoProtocolAdapter.class);
        when(seedanceAdapter.getProtocol()).thenReturn("seedance");

        NewApiVideoProtocolRouter router = new NewApiVideoProtocolRouter(List.of(genericAdapter, seedanceAdapter));
        NewApiVideoProtocolContext context = new NewApiVideoProtocolContext(
                AiModel.builder().code("bytedance/seedance-2-fast").build(),
                null,
                null,
                new JSONObject(),
                new AiModelMetadata("newapi", "newapi", "seedance", "generic")
        );

        assertSame(seedanceAdapter, router.resolve(context));
    }
}
