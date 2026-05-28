package com.stonewu.fusion.service.ai.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.storyboard.StoryboardService;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 查询分镜场次镜头列表工具（get_storyboard_scene_items）
 * <p>
 * 返回指定场次下的所有镜头详情，含完整的镜头信息（画面内容、运镜、对白、图片、视频等）。
 * 也支持通过 storyboardItemId 查询该镜头所在场次的所有镜头（用于获取上下文）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GetStoryboardSceneItemsToolExecutor implements ToolExecutor {

    private final StoryboardService storyboardService;
    private final AssetService assetService;

    @Override
    public String getToolName() {
        return "get_storyboard_scene_items";
    }

    @Override
    public String getDisplayName() {
        return "查询场次镜头列表";
    }

    @Override
    public String getToolDescription() {
        return """
                查询分镜场次下的所有镜头详情。支持两种查询方式：
                1. 通过 storyboardSceneId 直接查询场次下的所有镜头
                2. 通过 storyboardItemId 查询该镜头所在场次的所有镜头（自动定位场次）
                3. 兼容旧参数 sceneId，但推荐使用 storyboardSceneId

                如果同时提供 storyboardItemId 和 storyboardSceneId，会优先使用 storyboardItemId 自动定位所在场次。
                如果误把分镜条目ID填入 storyboardSceneId/sceneId，本工具会自动识别并按 storyboardItemId 重新查询，
                避免因大模型填错参数名导致“分镜场次不存在”。

                返回的每个镜头包含完整信息：画面内容、景别、运镜、对白、音效、图片URL、视频URL等。
                可用于获取上下文信息（上一个/下一个镜头），以便生成连贯的视频提示词。

                **资产引用解析**：每个镜头的 characterIds、propIds、sceneAssetItemId 会自动解析为带图片URL的资产引用信息，
                返回在 characterRefs、propRefs、sceneRef 字段中，包含子资产ID、名称、类型、图片URL、主资产描述、资产属性和生成提示词，
                可直接用于构建角色/场景/道具一致性锁定上下文，无需额外调用 query_asset_items。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "storyboardSceneId": {
                            "type": "integer",
                            "description": "分镜场次ID，直接查询该场次的所有镜头"
                        },
                        "storyboardItemId": {
                            "type": "integer",
                            "description": "分镜条目ID，自动找到所在场次并返回该场次所有镜头"
                        },
                        "sceneId": {
                            "type": "integer",
                            "description": "兼容旧参数：分镜场次ID，推荐改用 storyboardSceneId"
                        }
                    },
                    "additionalProperties": false
                }
                """;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long storyboardSceneId = positiveOrNull(params.getLong("storyboardSceneId"));
            Long sceneId = positiveOrNull(params.getLong("sceneId"));
            Long storyboardItemId = positiveOrNull(params.getLong("storyboardItemId"));
            Long sceneLikeId = storyboardSceneId != null ? storyboardSceneId : sceneId;

            if (storyboardSceneId == null) {
                storyboardSceneId = sceneId;
            }

            if (storyboardSceneId == null && storyboardItemId == null) {
                return errorResult("请提供有效的 storyboardSceneId 或 storyboardItemId");
            }

            // 如果提供了 storyboardItemId，先找到所在的场次
            StoryboardItem targetItem = null;
            Long targetItemId = storyboardItemId;
            if (storyboardItemId != null) {
                targetItem = storyboardService.getItemById(storyboardItemId);
                return buildResultForTargetItem(targetItem, null);
            }

            try {
                // 查询场次信息
                StoryboardScene scene = storyboardService.getSceneById(storyboardSceneId);

                // 查询该场次下的所有镜头
                List<StoryboardItem> items = storyboardService.listItemsByScene(storyboardSceneId);
                StoryboardItem sceneLikeItem = findItemOrNull(sceneLikeId);
                if (sceneLikeItem != null && items.stream().noneMatch(item -> sceneLikeId.equals(item.getId()))) {
                    return buildResultForTargetItem(sceneLikeItem,
                            "输入疑似将 storyboardItemId 填入了 storyboardSceneId/sceneId，已自动按镜头ID查询");
                }
                if (targetItemId != null && items.stream().noneMatch(item -> targetItemId.equals(item.getId()))) {
                    return buildFallbackItemsResult(targetItem, "关联场次列表中没有目标镜头，已降级为按镜头上下文查询");
                }

                return buildItemsResult(scene, targetItemId, items, null, null);
            } catch (Exception sceneError) {
                StoryboardItem sceneLikeItem = findItemOrNull(sceneLikeId);
                if (sceneLikeItem != null) {
                    log.warn("[get_storyboard_scene_items] 场次查询失败，输入疑似镜头ID，自动改按镜头查询: inputId={}, reason={}",
                            sceneLikeId, sceneError.getMessage());
                    return buildResultForTargetItem(sceneLikeItem,
                            "输入疑似将 storyboardItemId 填入了 storyboardSceneId/sceneId，已自动按镜头ID查询");
                }
                throw sceneError;
            }

        } catch (Exception e) {
            log.error("[get_storyboard_scene_items] 查询失败", e);
            return errorResult("查询失败: " + e.getMessage());
        }
    }

    private String buildResultForTargetItem(StoryboardItem targetItem, String warning) {
        Long storyboardSceneId = positiveOrNull(targetItem.getStoryboardSceneId());
        if (storyboardSceneId == null) {
            return buildFallbackItemsResult(targetItem, appendWarning(
                    "目标镜头未关联有效分镜场次，已降级为按镜头上下文查询", warning));
        }

        try {
            StoryboardScene scene = storyboardService.getSceneById(storyboardSceneId);
            List<StoryboardItem> items = storyboardService.listItemsByScene(storyboardSceneId);
            if (items.stream().noneMatch(item -> targetItem.getId().equals(item.getId()))) {
                return buildFallbackItemsResult(targetItem, appendWarning(
                        "关联场次列表中没有目标镜头，已降级为按镜头上下文查询", warning));
            }
            return buildItemsResult(scene, targetItem.getId(), items, null, warning);
        } catch (Exception sceneError) {
            log.warn("[get_storyboard_scene_items] 目标镜头关联场次查询失败，降级按镜头上下文返回: storyboardItemId={}, storyboardSceneId={}, reason={}",
                    targetItem.getId(), storyboardSceneId, sceneError.getMessage());
            return buildFallbackItemsResult(targetItem, appendWarning(
                    "关联分镜场次查询失败，已降级为按镜头上下文查询: " + sceneError.getMessage(), warning));
        }
    }

    private String buildFallbackItemsResult(StoryboardItem targetItem, String reason) {
        List<StoryboardItem> items = buildFallbackContextItems(targetItem);
        return buildItemsResult(null, targetItem.getId(), items, "storyboard_item_context", reason);
    }

    private List<StoryboardItem> buildFallbackContextItems(StoryboardItem targetItem) {
        List<StoryboardItem> contextItems = new ArrayList<>();
        if (targetItem.getStoryboardId() != null) {
            try {
                List<StoryboardItem> allItems = storyboardService.listItems(targetItem.getStoryboardId());
                Long episodeId = positiveOrNull(targetItem.getStoryboardEpisodeId());
                if (episodeId != null) {
                    List<StoryboardItem> episodeItems = allItems.stream()
                            .filter(item -> episodeId.equals(positiveOrNull(item.getStoryboardEpisodeId())))
                            .toList();
                    if (!episodeItems.isEmpty()) {
                        allItems = episodeItems;
                    }
                }
                contextItems.addAll(contextWindow(allItems, targetItem.getId()));
            } catch (Exception e) {
                log.warn("[get_storyboard_scene_items] 构建镜头降级上下文失败: storyboardItemId={}",
                        targetItem.getId(), e);
            }
        }
        if (contextItems.stream().noneMatch(item -> targetItem.getId().equals(item.getId()))) {
            contextItems.add(targetItem);
        }
        contextItems.sort(Comparator
                .comparing((StoryboardItem item) -> Optional.ofNullable(item.getSortOrder()).orElse(0))
                .thenComparing(item -> Optional.ofNullable(item.getId()).orElse(0L)));
        return contextItems;
    }

    private List<StoryboardItem> contextWindow(List<StoryboardItem> items, Long targetItemId) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int targetIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            if (targetItemId.equals(items.get(i).getId())) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) {
            return List.of();
        }
        int from = Math.max(0, targetIndex - 3);
        int to = Math.min(items.size(), targetIndex + 4);
        return new ArrayList<>(items.subList(from, to));
    }

    private String buildItemsResult(StoryboardScene scene, Long targetItemId, List<StoryboardItem> items,
                                    String fallbackMode, String warning) {
        // 收集所有镜头中引用的子资产ID，批量查询
        Set<Long> allAssetItemIds = new LinkedHashSet<>();
        for (StoryboardItem item : items) {
            collectAssetItemIds(allAssetItemIds, item.getCharacterIds());
            collectAssetItemIds(allAssetItemIds, item.getPropIds());
            Long sceneAssetItemId = positiveOrNull(item.getSceneAssetItemId());
            if (sceneAssetItemId != null) {
                allAssetItemIds.add(sceneAssetItemId);
            }
        }
        // 批量查询子资产信息
        Map<Long, AssetItem> assetItemMap = batchGetAssetItems(allAssetItemIds);
        Map<Long, Asset> assetMap = batchGetAssets(assetItemMap);

        JSONArray itemList = new JSONArray();
        for (StoryboardItem item : items) {
            JSONObject itemObj = JSONUtil.createObj()
                    .set("id", item.getId())
                    .set("storyboardId", item.getStoryboardId())
                    .set("storyboardEpisodeId", item.getStoryboardEpisodeId())
                    .set("storyboardSceneId", item.getStoryboardSceneId())
                    .set("shotNumber", item.getShotNumber())
                    .set("autoShotNumber", item.getAutoShotNumber())
                    .set("sortOrder", item.getSortOrder())
                    .set("shotType", item.getShotType())
                    .set("content", item.getContent())
                    .set("sceneExpectation", item.getSceneExpectation())
                    .set("dialogue", item.getDialogue())
                    .set("sound", item.getSound())
                    .set("soundEffect", item.getSoundEffect())
                    .set("music", item.getMusic())
                    .set("duration", item.getDuration())
                    .set("cameraMovement", item.getCameraMovement())
                    .set("cameraAngle", item.getCameraAngle())
                    .set("cameraEquipment", item.getCameraEquipment())
                    .set("focalLength", item.getFocalLength())
                    .set("transition", item.getTransition())
                    .set("imageUrl", item.getImageUrl())
                    .set("generatedImageUrl", item.getGeneratedImageUrl())
                    .set("videoUrl", item.getVideoUrl())
                    .set("generatedVideoUrl", item.getGeneratedVideoUrl())
                    .set("videoPrompt", item.getVideoPrompt())
                    .set("characterIds", item.getCharacterIds())
                    .set("sceneAssetItemId", item.getSceneAssetItemId())
                    .set("propIds", item.getPropIds())
                    .set("remark", item.getRemark());

            // 内联角色参考图信息
            JSONArray characterRefs = buildAssetRefs(item.getCharacterIds(), assetItemMap, assetMap);
            if (!characterRefs.isEmpty()) {
                itemObj.set("characterRefs", characterRefs);
            }

            // 内联道具参考图信息
            JSONArray propRefs = buildAssetRefs(item.getPropIds(), assetItemMap, assetMap);
            if (!propRefs.isEmpty()) {
                itemObj.set("propRefs", propRefs);
            }

            // 内联场景参考图信息
            Long sceneAssetItemId = positiveOrNull(item.getSceneAssetItemId());
            if (sceneAssetItemId != null) {
                AssetItem sceneAssetItem = assetItemMap.get(sceneAssetItemId);
                if (sceneAssetItem != null) {
                    itemObj.set("sceneRef",
                            buildSingleAssetRef(sceneAssetItem, assetMap.get(sceneAssetItem.getAssetId())));
                }
            }

            // 标记当前目标镜头
            if (targetItemId != null && targetItemId.equals(item.getId())) {
                itemObj.set("isCurrentTarget", true);
            }

            itemList.add(itemObj);
        }

        JSONObject result = JSONUtil.createObj()
                .set("status", "success")
                .set("storyboardSceneId", scene != null ? scene.getId() : null)
                .set("sceneName", scene != null ? scene.getSceneHeading() : null)
                .set("totalItems", items.size())
                .set("items", itemList);
        if (fallbackMode != null) {
            result.set("fallbackMode", fallbackMode);
        }
        if (warning != null) {
            result.set("warning", warning);
        }
        return result.toString();
    }

    private Long positiveOrNull(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private StoryboardItem findItemOrNull(Long id) {
        Long itemId = positiveOrNull(id);
        if (itemId == null) {
            return null;
        }
        try {
            return storyboardService.getItemById(itemId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String appendWarning(String primary, String secondary) {
        if (StrUtil.isBlank(secondary)) {
            return primary;
        }
        return primary + "；" + secondary;
    }

    private String errorResult(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }

    /**
     * 从 JSON 数组字符串中提取子资产 ID 到集合
     */
    private void collectAssetItemIds(Set<Long> ids, String jsonArrayStr) {
        if (StrUtil.isBlank(jsonArrayStr)) return;
        try {
            JSONArray arr = JSONUtil.parseArray(jsonArrayStr);
            for (int i = 0; i < arr.size(); i++) {
                Long id = arr.getLong(i);
                if (id != null) ids.add(id);
            }
        } catch (Exception e) {
            log.warn("[get_storyboard_scene_items] 解析子资产ID列表失败: {}", jsonArrayStr, e);
        }
    }

    /**
     * 批量查询子资产信息，返回 id → AssetItem 映射
     */
    private Map<Long, AssetItem> batchGetAssetItems(Set<Long> ids) {
        Map<Long, AssetItem> map = new HashMap<>();
        for (Long id : ids) {
            try {
                AssetItem item = assetService.getItemById(id);
                map.put(id, item);
            } catch (Exception e) {
                log.warn("[get_storyboard_scene_items] 查询子资产失败: id={}", id, e);
            }
        }
        return map;
    }

    /**
     * 批量查询子资产所属的主资产，用于补充稳定外观描述和主资产类型。
     */
    private Map<Long, Asset> batchGetAssets(Map<Long, AssetItem> assetItemMap) {
        Map<Long, Asset> map = new HashMap<>();
        for (AssetItem item : assetItemMap.values()) {
            Long assetId = positiveOrNull(item.getAssetId());
            if (assetId == null || map.containsKey(assetId)) {
                continue;
            }
            try {
                Asset asset = assetService.getById(assetId);
                map.put(assetId, asset);
            } catch (Exception e) {
                log.warn("[get_storyboard_scene_items] 查询主资产失败 id={}", assetId, e);
            }
        }
        return map;
    }

    /**
     * 根据 ID 列表 JSON 构建资产引用数组
     */
    private JSONArray buildAssetRefs(String idsJson, Map<Long, AssetItem> assetItemMap, Map<Long, Asset> assetMap) {
        JSONArray refs = new JSONArray();
        if (StrUtil.isBlank(idsJson)) return refs;
        try {
            JSONArray arr = JSONUtil.parseArray(idsJson);
            for (int i = 0; i < arr.size(); i++) {
                Long id = arr.getLong(i);
                if (id == null) continue;
                AssetItem assetItem = assetItemMap.get(id);
                if (assetItem != null) {
                    refs.add(buildSingleAssetRef(assetItem, assetMap.get(assetItem.getAssetId())));
                }
            }
        } catch (Exception e) {
            log.warn("[get_storyboard_scene_items] 构建资产引用失败: {}", idsJson, e);
        }
        return refs;
    }

    /**
     * 构建单个子资产的引用信息
     */
    private JSONObject buildSingleAssetRef(AssetItem assetItem, Asset asset) {
        return JSONUtil.createObj()
                .set("assetItemId", assetItem.getId())
                .set("assetId", assetItem.getAssetId())
                .set("assetName", asset != null ? asset.getName() : null)
                .set("assetType", asset != null ? asset.getType() : null)
                .set("assetDescription", asset != null ? asset.getDescription() : null)
                .set("assetProperties", asset != null ? asset.getProperties() : null)
                .set("assetPrompt", asset != null ? asset.getAiPrompt() : null)
                .set("name", assetItem.getName())
                .set("itemType", assetItem.getItemType())
                .set("imageUrl", assetItem.getImageUrl())
                .set("thumbnailUrl", assetItem.getThumbnailUrl())
                .set("itemProperties", assetItem.getProperties())
                .set("itemPrompt", assetItem.getAiPrompt());
    }
}
