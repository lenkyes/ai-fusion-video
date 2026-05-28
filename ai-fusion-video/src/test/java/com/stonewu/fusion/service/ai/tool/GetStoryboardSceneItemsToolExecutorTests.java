package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetStoryboardSceneItemsToolExecutorTests {

    @Test
    void treatsSceneIdAsStoryboardItemIdWhenModelPassesWrongField() {
        StoryboardService storyboardService = mock(StoryboardService.class);
        AssetService assetService = mock(AssetService.class);

        StoryboardItem target = StoryboardItem.builder()
                .id(3307L)
                .storyboardId(12L)
                .storyboardEpisodeId(22L)
                .storyboardSceneId(77L)
                .content("男主走进雨夜街口")
                .build();
        StoryboardScene scene = StoryboardScene.builder()
                .id(77L)
                .sceneHeading("雨夜街口")
                .build();

        when(storyboardService.getSceneById(3307L)).thenThrow(new RuntimeException("分镜场次不存在"));
        when(storyboardService.getItemById(3307L)).thenReturn(target);
        when(storyboardService.getSceneById(77L)).thenReturn(scene);
        when(storyboardService.listItemsByScene(77L)).thenReturn(List.of(target));

        GetStoryboardSceneItemsToolExecutor executor =
                new GetStoryboardSceneItemsToolExecutor(storyboardService, assetService);

        String result = executor.execute("{\"storyboardSceneId\":3307}",
                ToolExecutionContext.builder().userId(1L).build());
        JSONObject json = JSONUtil.parseObj(result);

        assertThat(json.getStr("status")).isEqualTo("success");
        assertThat(json.getLong("storyboardSceneId")).isEqualTo(77L);
        assertThat(json.getStr("warning")).contains("已自动按镜头ID查询");
        assertThat(json.getJSONArray("items").getJSONObject(0).getBool("isCurrentTarget")).isTrue();
    }
}
