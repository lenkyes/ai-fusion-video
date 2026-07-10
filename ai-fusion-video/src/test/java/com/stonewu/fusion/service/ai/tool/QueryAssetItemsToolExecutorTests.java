package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.project.ProjectService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryAssetItemsToolExecutorTests {

    @Test
    void returnsDeterministicAppearanceAndCanonicalIds() {
        AssetService assetService = mock(AssetService.class);
        ProjectService projectService = mock(ProjectService.class);
        Asset asset = Asset.builder().id(9L).type("character").name("张三").build();
        AssetItem oldAge = AssetItem.builder()
                .id(101L)
                .assetId(9L)
                .itemType("age")
                .name("老年张三")
                .build();
        AssetItem oldAgeThreeView = AssetItem.builder()
                .id(102L)
                .assetId(9L)
                .parentItemId(101L)
                .itemType("three_view")
                .name("老年张三 三视图")
                .build();
        when(assetService.getById(9L)).thenReturn(asset);
        when(assetService.canAccessAsset(asset, 7L)).thenReturn(true);
        when(assetService.listItems(9L)).thenReturn(List.of(oldAge, oldAgeThreeView));
        when(assetService.resolveAppearanceItemId(oldAge)).thenReturn(101L);
        when(assetService.resolveAppearanceItemId(oldAgeThreeView)).thenReturn(101L);

        QueryAssetItemsToolExecutor executor =
                new QueryAssetItemsToolExecutor(assetService, projectService);
        JSONObject result = JSONUtil.parseObj(executor.execute("{\"assetId\":9}",
                ToolExecutionContext.builder().userId(7L).build()));
        JSONArray items = result.getJSONArray("items");

        assertThat(items.getJSONObject(0).getLong("appearanceItemId")).isEqualTo(101L);
        assertThat(items.getJSONObject(0).getLong("canonicalThreeViewItemId")).isEqualTo(102L);
        assertThat(items.getJSONObject(1).getLong("parentItemId")).isEqualTo(101L);
        assertThat(items.getJSONObject(1).getLong("appearanceItemId")).isEqualTo(101L);
        assertThat(items.getJSONObject(1).getLong("canonicalThreeViewItemId")).isEqualTo(102L);
        verify(assetService).ensureCharacterThreeViewItems(asset);
    }

    @Test
    void doesNotExposeCharacterRelationshipFieldsForSceneItems() {
        AssetService assetService = mock(AssetService.class);
        ProjectService projectService = mock(ProjectService.class);
        Asset asset = Asset.builder().id(19L).type("scene").name("街道").build();
        AssetItem sceneItem = AssetItem.builder()
                .id(201L)
                .assetId(19L)
                .parentItemId(999L)
                .itemType("initial")
                .build();
        when(assetService.getById(19L)).thenReturn(asset);
        when(assetService.canAccessAsset(asset, 7L)).thenReturn(true);
        when(assetService.listItems(19L)).thenReturn(List.of(sceneItem));

        QueryAssetItemsToolExecutor executor =
                new QueryAssetItemsToolExecutor(assetService, projectService);
        JSONObject item = JSONUtil.parseObj(executor.execute("{\"assetId\":19}",
                        ToolExecutionContext.builder().userId(7L).build()))
                .getJSONArray("items")
                .getJSONObject(0);

        assertThat(item.get("parentItemId")).isNull();
        assertThat(item.get("appearanceItemId")).isNull();
        assertThat(item.get("canonicalThreeViewItemId")).isNull();
        verify(assetService, never()).resolveAppearanceItemId(sceneItem);
    }
}
