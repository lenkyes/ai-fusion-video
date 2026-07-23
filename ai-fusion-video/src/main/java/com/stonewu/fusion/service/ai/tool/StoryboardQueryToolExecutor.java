package com.stonewu.fusion.service.ai.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 查询分镜详情工具（get_storyboard）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StoryboardQueryToolExecutor implements ToolExecutor {

    private final StoryboardService storyboardService;

    @Override
    public String getToolName() {
        return "get_storyboard";
    }

    @Override
    public String getDisplayName() {
        return "查询分镜详情";
    }

    @Override
    public String getToolDescription() {
        return """
                查询分镜脚本的详情，包含所有分镜条目信息。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "storyboardId": {
                            "type": "number",
                            "description": "分镜ID"
                        }
                    },
                    "required": ["storyboardId"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long storyboardId = params.getLong("storyboardId");
            if (storyboardId == null) {
                return JSONUtil.createObj().set("status", "error").set("message", "缺少 storyboardId").toString();
            }

            Storyboard storyboard = storyboardService.getById(storyboardId);
            List<StoryboardItem> items = storyboardService.listItems(storyboardId);
            int originalTotalItems = items.size();
            Set<Long> selectedItemIds = selectedStoryboardItemIds(context);
            boolean incompleteOnly = context != null && context.getRequestContext() != null
                    && Boolean.TRUE.equals(context.getRequestContext().get("incompleteFramesOnly"));
            if (!selectedItemIds.isEmpty()) {
                items = items.stream()
                        .filter(item -> selectedItemIds.contains(item.getId()))
                        .toList();
                log.info("get_storyboard 按请求上下文裁剪: storyboardId={}, selected={}, returned={}, original={}",
                        storyboardId, selectedItemIds.size(), items.size(), originalTotalItems);
            }
            if (incompleteOnly) {
                items = items.stream().filter(item -> {
                    JSONObject data = StrUtil.isBlank(item.getCustomData()) ? new JSONObject() : JSONUtil.parseObj(item.getCustomData());
                    return StrUtil.isBlank(data.getStr("firstFrameImageUrl"))
                            || StrUtil.isBlank(data.getStr("lastFrameImageUrl"));
                }).toList();
            }

            JSONArray itemList = new JSONArray();
            for (StoryboardItem item : items) {
                itemList.add(JSONUtil.createObj()
                        .set("id", item.getId())
                        .set("shotNumber", item.getShotNumber())
                        .set("autoShotNumber", item.getAutoShotNumber())
                        .set("shotType", item.getShotType())
                        .set("content", item.getContent())
                        .set("sceneExpectation", item.getSceneExpectation())
                        .set("dialogue", item.getDialogue())
                        .set("sound", item.getSound())
                        .set("duration", item.getDuration())
                        .set("cameraMovement", item.getCameraMovement())
                        .set("cameraAngle", item.getCameraAngle())
                        .set("transition", item.getTransition())
                        .set("imageUrl", item.getImageUrl())
                        .set("generatedImageUrl", item.getGeneratedImageUrl())
                        .set("videoUrl", item.getVideoUrl())
                        .set("generatedVideoUrl", item.getGeneratedVideoUrl())
                        .set("firstFrameImageUrl", frameValue(item, "firstFrameImageUrl"))
                        .set("lastFrameImageUrl", frameValue(item, "lastFrameImageUrl"))
                        .set("videoPrompt", item.getVideoPrompt()));
            }

            return JSONUtil.createObj()
                    .set("storyboardId", storyboard.getId())
                    .set("title", storyboard.getTitle())
                    .set("description", storyboard.getDescription())
                    .set("totalItems", items.size())
                    .set("originalTotalItems", originalTotalItems)
                    .set("selectionApplied", !selectedItemIds.isEmpty())
                    .set("items", itemList)
                    .toString();
        } catch (Exception e) {
            log.error("查询分镜详情失败", e);
            return JSONUtil.createObj().set("status", "error").set("message", "查询失败: " + e.getMessage()).toString();
        }
    }

    private String frameValue(StoryboardItem item, String key) {
        if (StrUtil.isBlank(item.getCustomData())) return null;
        try { return JSONUtil.parseObj(item.getCustomData()).getStr(key); }
        catch (Exception ignored) { return null; }
    }

    private Set<Long> selectedStoryboardItemIds(ToolExecutionContext context) {
        Set<Long> ids = new LinkedHashSet<>();
        if (context == null || context.getRequestContext() == null) {
            return ids;
        }
        Object value = context.getRequestContext().get("selectedStoryboardItemIds");
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addLong(ids, item);
            }
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addLong(ids, java.lang.reflect.Array.get(value, i));
            }
        }
        return ids;
    }

    private void addLong(Set<Long> ids, Object value) {
        if (value instanceof Number number) {
            ids.add(number.longValue());
            return;
        }
        if (value != null) {
            try {
                ids.add(Long.valueOf(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                log.warn("忽略非法的 selectedStoryboardItemId: {}", value);
            }
        }
    }
}
