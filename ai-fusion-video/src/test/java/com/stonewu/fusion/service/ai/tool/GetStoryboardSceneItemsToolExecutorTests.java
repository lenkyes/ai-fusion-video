package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
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
    void itemQueryReturnsOnlyPreviousCurrentAndNextShot() {
        StoryboardService storyboardService = mock(StoryboardService.class);
        AssetService assetService = mock(AssetService.class);
        StoryboardScene scene = StoryboardScene.builder().id(486L).sceneHeading("1-1").build();
        List<StoryboardItem> items = java.util.stream.LongStream.rangeClosed(1, 9)
                .mapToObj(id -> StoryboardItem.builder()
                        .id(id)
                        .storyboardSceneId(486L)
                        .sortOrder((int) id)
                        .build())
                .toList();
        when(storyboardService.getItemById(5L)).thenReturn(items.get(4));
        when(storyboardService.getSceneById(486L)).thenReturn(scene);
        when(storyboardService.listItemsByScene(486L)).thenReturn(items);

        GetStoryboardSceneItemsToolExecutor executor =
                new GetStoryboardSceneItemsToolExecutor(storyboardService, assetService);

        JSONObject result = JSONUtil.parseObj(executor.execute("{\"storyboardItemId\":5}",
                ToolExecutionContext.builder().userId(1L).build()));

        assertThat(result.getInt("totalItems")).isEqualTo(3);
        assertThat(result.getStr("fallbackMode")).isEqualTo("target_context");
        assertThat(result.getJSONArray("items").stream()
                .map(value -> ((JSONObject) value).getLong("id")))
                .containsExactly(4L, 5L, 6L);
    }

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

    @Test
    void resolvesCharacterAppearanceToItsOwnCanonicalThreeView() {
        StoryboardService storyboardService = mock(StoryboardService.class);
        AssetService assetService = mock(AssetService.class);

        StoryboardItem target = StoryboardItem.builder()
                .id(4401L)
                .storyboardId(12L)
                .storyboardEpisodeId(22L)
                .storyboardSceneId(77L)
                .characterIds("[101]")
                .build();
        StoryboardScene scene = StoryboardScene.builder().id(77L).sceneHeading("十年后").build();
        Asset asset = Asset.builder().id(9L).type("character").name("张三").build();
        AssetItem oldAgeAppearance = AssetItem.builder()
                .id(101L)
                .assetId(9L)
                .itemType("age")
                .name("老年张三")
                .properties("{\"age\":\"老年\"}")
                .build();
        AssetItem oldAgeThreeView = AssetItem.builder()
                .id(102L)
                .assetId(9L)
                .parentItemId(101L)
                .itemType("three_view")
                .name("老年张三 三视图")
                .imageUrl("https://example.com/old-age-three-view.png")
                .build();

        when(storyboardService.getItemById(4401L)).thenReturn(target);
        when(storyboardService.getSceneById(77L)).thenReturn(scene);
        when(storyboardService.listItemsByScene(77L)).thenReturn(List.of(target));
        when(assetService.getItemById(101L)).thenReturn(oldAgeAppearance);
        when(assetService.getById(9L)).thenReturn(asset);
        when(assetService.resolveAppearanceItem(oldAgeAppearance)).thenReturn(oldAgeAppearance);
        when(assetService.findCanonicalThreeViewItem(101L)).thenReturn(oldAgeThreeView);
        when(assetService.resolveCanonicalReferenceItem(oldAgeAppearance)).thenReturn(oldAgeThreeView);

        GetStoryboardSceneItemsToolExecutor executor =
                new GetStoryboardSceneItemsToolExecutor(storyboardService, assetService);

        JSONObject result = JSONUtil.parseObj(executor.execute("{\"storyboardItemId\":4401}",
                ToolExecutionContext.builder().userId(1L).build()));
        JSONObject characterRef = result.getJSONArray("items")
                .getJSONObject(0)
                .getJSONArray("characterRefs")
                .getJSONObject(0);

        assertThat(characterRef.getLong("selectedAssetItemId")).isEqualTo(101L);
        assertThat(characterRef.getLong("assetItemId")).isEqualTo(102L);
        assertThat(characterRef.getLong("appearanceItemId")).isEqualTo(101L);
        assertThat(characterRef.getLong("canonicalThreeViewItemId")).isEqualTo(102L);
        assertThat(characterRef.getStr("itemType")).isEqualTo("three_view");
        assertThat(characterRef.getStr("imageUrl"))
                .isEqualTo("https://example.com/old-age-three-view.png");
        assertThat(characterRef.getStr("itemProperties")).isEqualTo("{\"age\":\"老年\"}");
    }
}
