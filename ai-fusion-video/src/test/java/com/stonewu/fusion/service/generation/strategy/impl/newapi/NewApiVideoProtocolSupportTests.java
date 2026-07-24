package com.stonewu.fusion.service.generation.strategy.impl.newapi;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.service.ai.model.AiModelMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewApiVideoProtocolSupportTests {

    private final NewApiVideoProtocolSupport support = new NewApiVideoProtocolSupport();

    @Test
    void shouldBuildGrokImagineWithOfficialXaiBody() {
        VideoTask task = VideoTask.builder().prompt("cinematic shot")
                .firstFrameImageUrl("https://example.com/first.png")
                .referenceImageUrls(JSONUtil.toJsonStr(List.of(
                        "https://example.com/ref-1.png",
                        "https://example.com/ref-2.png")))
                .ratio("16:9").resolution("720p").duration(8).build();
        NewApiVideoProtocolContext context = new NewApiVideoProtocolContext(
                AiModel.builder().code("grok-imagine-video-1-5-preview").build(), null, task,
                new JSONObject(), new AiModelMetadata("newapi", "newapi", "grok_imagine", "grok_imagine"));

        JSONObject body = support.buildGrokImagineSubmitBody(context);
        assertEquals("grok-imagine-video-1.5", body.getStr("model"));
        assertEquals("cinematic shot", body.getStr("prompt"));
        assertEquals("https://example.com/first.png",
                body.getJSONObject("image").getStr("url"));
        assertEquals("16:9", body.getStr("aspect_ratio"));
        assertEquals("720p", body.getStr("resolution"));
        assertEquals(8, body.getInt("duration"));
    }

    @Test
    void shouldPreserveConfiguredGrokImagineVideoModelCode() {
        VideoTask task = VideoTask.builder()
                .prompt("cinematic shot")
                .firstFrameImageUrl("https://cdn.example.com/media/first.png")
                .build();
        NewApiVideoProtocolContext context = new NewApiVideoProtocolContext(
                AiModel.builder().code("grok-imagine-video").build(), null, task,
                new JSONObject(), new AiModelMetadata("newapi", "newapi", "grok_imagine", "grok_imagine"));

        JSONObject body = support.buildGrokImagineSubmitBody(context);

        assertEquals("grok-imagine-video", body.getStr("model"));
        assertEquals("https://cdn.example.com/media/first.png",
                body.getJSONObject("image").getStr("url"));
    }

    @Test
    void shouldUseTheFirstOrderedReferenceAsGrokInputImage() {
        VideoTask task = VideoTask.builder()
                .prompt("transition from start to end")
                .referenceImageUrls(JSONUtil.toJsonStr(List.of(
                        "https://cdn.example.com/generated-first-frame.png",
                        "https://cdn.example.com/generated-last-frame.png",
                        "https://cdn.example.com/character-reference.png")))
                .build();
        NewApiVideoProtocolContext context = new NewApiVideoProtocolContext(
                AiModel.builder().code("grok-imagine-video").build(), null, task,
                new JSONObject(), new AiModelMetadata("newapi", "newapi", "grok_imagine", "grok_imagine"));

        JSONObject body = support.buildGrokImagineSubmitBody(context);

        assertEquals("https://cdn.example.com/generated-first-frame.png",
                body.getJSONObject("image").getStr("url"));
    }

    @Test
    void shouldBuildSeedanceContentGenerationBody() {
        VideoTask task = VideoTask.builder()
                .prompt("cinematic shot")
                .firstFrameImageUrl("https://example.com/first.png")
                .lastFrameImageUrl("https://example.com/last.png")
                .referenceImageUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.png")))
                .referenceVideoUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.mp4")))
                .referenceAudioUrls(JSONUtil.toJsonStr(List.of("https://example.com/ref.mp3")))
                .ratio("9:16")
                .duration(5)
                .generateAudio(true)
                .cameraFixed(false)
                .build();
        NewApiVideoProtocolContext context = new NewApiVideoProtocolContext(
                AiModel.builder().code("bytedance/seedance-2-fast").build(),
                null,
                task,
                new JSONObject(),
                new AiModelMetadata("newapi", "newapi", "seedance", "seedance")
        );

        JSONObject body = support.buildSeedanceContentGenerationBody(context);
        JSONArray contents = body.getJSONArray("content");

        assertEquals("bytedance/seedance-2-fast", body.getStr("model"));
        assertEquals("9:16", body.getStr("ratio"));
        assertEquals(5L, body.getLong("duration"));
        assertTrue(body.getBool("generate_audio"));
        assertEquals(6, contents.size());
        assertEquals("text", contents.getJSONObject(0).getStr("type"));
        assertEquals("first_frame", contents.getJSONObject(1).getStr("role"));
        assertEquals("last_frame", contents.getJSONObject(2).getStr("role"));
        assertEquals("reference_image", contents.getJSONObject(3).getStr("role"));
        assertEquals("reference_video", contents.getJSONObject(4).getStr("role"));
        assertEquals("reference_audio", contents.getJSONObject(5).getStr("role"));
    }
}
