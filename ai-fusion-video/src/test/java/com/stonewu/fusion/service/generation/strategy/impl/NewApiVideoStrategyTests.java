package com.stonewu.fusion.service.generation.strategy.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.model.AiModelMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewApiVideoStrategyTests {

    private final NewApiVideoStrategy strategy = new NewApiVideoStrategy(null, null, null, null, null, null);

    @Test
    void shouldUseContentGenerationTaskPathForSeedanceModels() {
        ApiConfig apiConfig = ApiConfig.builder()
                .apiUrl("http://localhost:8080")
                .build();
        AiModelMetadata metadata = new AiModelMetadata("newapi", "newapi", "seedance", "generic");

        assertEquals("http://localhost:8080/api/v3/contents/generations/tasks",
                strategy.resolveSubmitUrl(apiConfig, new JSONObject(), metadata));
        assertEquals("http://localhost:8080/api/v3/contents/generations/tasks/task-123",
                strategy.resolveQueryUrl(apiConfig, new JSONObject(), metadata, "task-123"));
    }

    @Test
    void shouldUseConfiguredVideoTaskPaths() {
        ApiConfig apiConfig = ApiConfig.builder()
                .apiUrl("http://localhost:8080/v1")
                .build();
        JSONObject config = JSONUtil.createObj()
                .set("videoSubmitPath", "/api/v3/contents/generations/tasks")
                .set("videoQueryPathTemplate", "/api/v3/contents/generations/tasks/{id}");
        AiModelMetadata metadata = new AiModelMetadata("newapi", "newapi", "generic", "generic");

        assertEquals("http://localhost:8080/api/v3/contents/generations/tasks",
                strategy.resolveSubmitUrl(apiConfig, config, metadata));
        assertEquals("http://localhost:8080/api/v3/contents/generations/tasks/task-456",
                strategy.resolveQueryUrl(apiConfig, config, metadata, "task-456"));
    }

    @Test
    void shouldUseOfficialXaiTaskPathsForGrokImagine() {
        ApiConfig apiConfig = ApiConfig.builder().apiUrl("http://localhost:8080").build();
        AiModelMetadata metadata = new AiModelMetadata("newapi", "newapi", "grok_imagine", "grok_imagine");

        assertEquals("http://localhost:8080/v1/videos/generations",
                strategy.resolveSubmitUrl(apiConfig, new JSONObject(), metadata));
        assertEquals("http://localhost:8080/v1/videos/task-123",
                strategy.resolveQueryUrl(apiConfig, new JSONObject(), metadata, "task-123"));
    }

    @Test
    void shouldRejectRelativeApiBaseUrlBeforeBuildingRequest() {
        ApiConfig apiConfig = ApiConfig.builder().apiUrl("/").build();
        AiModelMetadata metadata = new AiModelMetadata("newapi", "newapi", "grok_imagine", "grok_imagine");

        assertThrows(com.stonewu.fusion.common.BusinessException.class,
                () -> strategy.resolveSubmitUrl(apiConfig, new JSONObject(), metadata));
    }

    @Test
    void shouldExtractOfficialXaiRequestId() {
        assertEquals("request-123", strategy.extractTaskId("""
                {"request_id":"request-123"}
                """));
    }

    @Test
    void shouldParseOfficialXaiVideoResult() {
        NewApiVideoStrategy.NewApiVideoResult result = strategy.parseQueryResult("""
                {
                  "status": "done",
                  "video": {"url": "https://cdn.example.com/grok.mp4"}
                }
                """);

        assertEquals("done", result.status());
        assertEquals("https://cdn.example.com/grok.mp4", result.videoUrl());
    }

    @Test
    void shouldResolveRelativeGrokVideoContentUrlAgainstUpstreamOrigin() {
        NewApiVideoStrategy.NewApiVideoResult result = strategy.parseQueryResult("""
                {
                  "model": "grok-imagine-video",
                  "progress": 100,
                  "status": "done",
                  "usage": {"cost_in_usd_ticks": 3020000000},
                  "video": {
                    "duration": 6,
                    "respect_moderation": true,
                    "url": "/v1/videos/271eab65-e70f-95b4-8b0c-f0a6fc21afcf/content"
                  }
                }
                """, "https://api.x.ai/v1/videos/request-123");

        assertEquals("done", result.status());
        assertEquals(6, result.duration());
        assertEquals("https://api.x.ai/v1/videos/271eab65-e70f-95b4-8b0c-f0a6fc21afcf/content",
                result.videoUrl());
    }

    @Test
    void shouldOnlyAuthenticateMediaDownloadsFromApiOrigin() {
        assertTrue(strategy.isSameOrigin(
                "https://aigpt8.cn/v1",
                "https://aigpt8.cn/v1/videos/video-123/content"));
        assertFalse(strategy.isSameOrigin(
                "https://aigpt8.cn/v1",
                "https://cdn.example.com/videos/video-123.mp4"));
        assertFalse(strategy.isSameOrigin(
                "https://aigpt8.cn/v1",
                "http://aigpt8.cn/v1/videos/video-123/content"));
    }

    @Test
    void shouldAvoidDuplicatingApiV3WhenBaseUrlAlreadyContainsApiV3() {
        ApiConfig apiConfig = ApiConfig.builder()
                .apiUrl("http://localhost:8080/api/v3")
                .build();
        AiModelMetadata metadata = new AiModelMetadata("newapi", "newapi", "seedance", "generic");

        assertEquals("http://localhost:8080/api/v3/contents/generations/tasks",
                strategy.resolveSubmitUrl(apiConfig, new JSONObject(), metadata));
    }

    @Test
    void shouldExtractTaskIdFromNestedTaskShape() {
        String taskId = strategy.extractTaskId("""
                {
                  "data": {
                    "task": {
                      "id": "task-789"
                    }
                  }
                }
                """);

        assertEquals("task-789", taskId);
    }

    @Test
    void shouldParseV3TaskResultWithNestedOutputVideo() {
        NewApiVideoStrategy.NewApiVideoResult result = strategy.parseQueryResult("""
                {
                  "data": {
                    "status": "success",
                    "outputs": [
                      {
                        "video_url": "http://cdn.example.com/video.mp4",
                        "cover_url": "http://cdn.example.com/cover.jpg"
                      }
                    ],
                    "duration": 5
                  }
                }
                """);

        assertEquals("success", result.status());
        assertEquals("http://cdn.example.com/video.mp4", result.videoUrl());
        assertEquals("http://cdn.example.com/cover.jpg", result.coverUrl());
        assertEquals(5, result.duration());
    }

    @Test
    void shouldParseV3TaskResultWithRootContentVideo() {
        NewApiVideoStrategy.NewApiVideoResult result = strategy.parseQueryResult("""
                {
                  "status": "succeeded",
                  "content": {
                    "video_url": "http://cdn.example.com/root-content-video.mp4",
                    "last_frame_url": "http://cdn.example.com/last.png",
                    "duration": 6
                  }
                }
                """);

        assertEquals("succeeded", result.status());
        assertEquals("http://cdn.example.com/root-content-video.mp4", result.videoUrl());
        assertEquals("http://cdn.example.com/last.png", result.lastFrameUrl());
        assertEquals(6, result.duration());
    }

    @Test
    void shouldParseV3TaskResultWithContentArrayVideoObject() {
        NewApiVideoStrategy.NewApiVideoResult result = strategy.parseQueryResult("""
                {
                  "status": "succeeded",
                  "content": [
                    {
                      "type": "text",
                      "text": "ok"
                    },
                    {
                      "type": "video_url",
                      "video_url": {
                        "url": "http://cdn.example.com/content-array-video.mp4"
                      }
                    },
                    {
                      "type": "last_frame_url",
                      "last_frame_url": {
                        "url": "http://cdn.example.com/content-array-last.png"
                      }
                    }
                  ]
                }
                """);

        assertEquals("succeeded", result.status());
        assertEquals("http://cdn.example.com/content-array-video.mp4", result.videoUrl());
        assertEquals("http://cdn.example.com/content-array-last.png", result.lastFrameUrl());
    }
}
