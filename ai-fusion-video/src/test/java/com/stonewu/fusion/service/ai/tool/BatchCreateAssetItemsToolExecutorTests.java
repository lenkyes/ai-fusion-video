package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.asset.AssetService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchCreateAssetItemsToolExecutorTests {

    @Test
    void returnsAutomaticallyPairedThreeViewIdsForCreatedAppearance() {
        AssetService assetService = mock(AssetService.class);
        Asset asset = Asset.builder().id(9L).type("character").name("张三").build();
        AssetItem canonicalThreeView = AssetItem.builder()
                .id(102L)
                .assetId(9L)
                .parentItemId(101L)
                .itemType("three_view")
                .build();
        when(assetService.getById(9L)).thenReturn(asset);
        when(assetService.canAccessAsset(asset, 7L)).thenReturn(true);
        when(assetService.listItems(9L)).thenReturn(List.of());
        when(assetService.createItem(any(AssetItem.class))).thenAnswer(invocation -> {
            AssetItem item = invocation.getArgument(0);
            item.setId(101L);
            return item;
        });
        when(assetService.resolveAppearanceItemId(any(AssetItem.class))).thenReturn(101L);
        when(assetService.findCanonicalThreeViewItem(101L)).thenReturn(canonicalThreeView);

        BatchCreateAssetItemsToolExecutor executor = new BatchCreateAssetItemsToolExecutor(assetService);
        JSONObject result = JSONUtil.parseObj(executor.execute("""
                {
                  "assetId": 9,
                  "items": [
                    {
                      "name": "老年张三",
                      "itemType": "age",
                      "properties": { "age": "老年" }
                    }
                  ]
                }
                """, ToolExecutionContext.builder().userId(7L).build()));
        JSONObject created = result.getJSONArray("created").getJSONObject(0);

        assertThat(created.getLong("assetItemId")).isEqualTo(101L);
        assertThat(created.getLong("appearanceItemId")).isEqualTo(101L);
        assertThat(created.getLong("canonicalThreeViewItemId")).isEqualTo(102L);
        ArgumentCaptor<AssetItem> captor = ArgumentCaptor.forClass(AssetItem.class);
        verify(assetService).createItem(captor.capture());
        assertThat(captor.getValue().getItemType()).isEqualTo("age");
        assertThat(captor.getValue().getParentItemId()).isNull();
    }
}
