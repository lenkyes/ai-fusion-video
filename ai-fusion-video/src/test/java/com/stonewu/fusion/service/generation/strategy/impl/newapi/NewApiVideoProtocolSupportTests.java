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
    void shouldBuildGrokImagineWithSeedanceCompatibleBody() {
        VideoTask task = VideoTask.builder().prompt("cinematic shot")
                .firstFrameImageUrl("https://example.com/first.png")
                .ratio("16:9").resolution("720p").duration(8).build();
        NewApiVideoProtocolContext context = new NewApiVideoProtocolContext(
                AiModel.builder().code("grok-imagine-video-1-5-preview").build(), null, task,
                new JSONObject(), new AiModelMetadata("newapi", "newapi", "grok_imagine", "grok_imagine"));

        JSONObject body = support.buildSeedanceContentGenerationBody(context);
        JSONArray content = body.getJSONArray("content");
        assertEquals("grok-imagine-video-1-5-preview", body.getStr("model"));
        assertEquals("cinematic shot", content.getJSONObject(0).getStr("text"));
        assertEquals("https://example.com/first.png",
                content.getJSONObject(1).getJSONObject("image_url").getStr("url"));
        assertEquals("16:9", body.getStr("ratio"));
        assertEquals("720p", body.getStr("resolution"));
        assertEquals(8L, body.getLong("duration"));
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
