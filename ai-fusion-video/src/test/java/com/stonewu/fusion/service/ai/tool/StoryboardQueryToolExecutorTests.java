package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoryboardQueryToolExecutorTests {

    @Test
    void returnsOnlyItemsSelectedByWorkflowContext() {
        StoryboardService storyboardService = mock(StoryboardService.class);
        when(storyboardService.getById(1L)).thenReturn(Storyboard.builder()
                .id(1L)
                .title("test")
                .build());
        when(storyboardService.listItems(1L)).thenReturn(List.of(
                StoryboardItem.builder().id(10L).content("not selected").build(),
                StoryboardItem.builder().id(20L).content("selected A").build(),
                StoryboardItem.builder().id(30L).content("not selected").build(),
                StoryboardItem.builder().id(40L).content("selected B").build()));

        ToolExecutionContext context = ToolExecutionContext.builder()
                .requestContext(Map.of("selectedStoryboardItemIds", List.of(20, 40)))
                .build();

        String result = new StoryboardQueryToolExecutor(storyboardService)
                .execute("{\"storyboardId\":1}", context);
        JSONObject json = JSONUtil.parseObj(result);

        assertThat(json.getInt("totalItems")).isEqualTo(2);
        assertThat(json.getInt("originalTotalItems")).isEqualTo(4);
        assertThat(json.getBool("selectionApplied")).isTrue();
        assertThat(json.getJSONArray("items").stream()
                .map(item -> ((JSONObject) item).getLong("id")))
                .containsExactly(20L, 40L);
    }
}
