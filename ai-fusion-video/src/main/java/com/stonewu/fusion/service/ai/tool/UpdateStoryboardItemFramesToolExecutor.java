package com.stonewu.fusion.service.ai.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import com.stonewu.fusion.service.storage.MediaStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class UpdateStoryboardItemFramesToolExecutor implements ToolExecutor {

    private final StoryboardService storyboardService;
    private final MediaStorageService mediaStorageService;

    @Override
    public String getToolName() {
        return "update_storyboard_item_frames";
    }

    @Override
    public String getDisplayName() {
        return "保存分镜首尾帧";
    }

    @Override
    public String getToolDescription() {
        return "将已经生成的首帧图和尾帧图保存到指定分镜镜头。两个 URL 必须都来自 generate_image 的成功结果。";
    }

    @Override
    public String getParametersSchema() {
        return JSONUtil.createObj()
                .set("type", "object")
                .set("properties", JSONUtil.createObj()
                        .set("storyboardItemId", JSONUtil.createObj().set("type", "integer"))
                        .set("firstFrameImageUrl", JSONUtil.createObj().set("type", "string"))
                        .set("lastFrameImageUrl", JSONUtil.createObj().set("type", "string")))
                .set("required", JSONUtil.parseArray("[\"storyboardItemId\",\"firstFrameImageUrl\",\"lastFrameImageUrl\"]"))
                .toString();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            Long itemId = params.getLong("storyboardItemId");
            String firstFrame = StrUtil.trim(params.getStr("firstFrameImageUrl"));
            String lastFrame = StrUtil.trim(params.getStr("lastFrameImageUrl"));
            if (itemId == null || itemId <= 0 || StrUtil.isBlank(firstFrame) || StrUtil.isBlank(lastFrame)) {
                return error("storyboardItemId、firstFrameImageUrl 和 lastFrameImageUrl 均不能为空");
            }

            firstFrame = mediaStorageService.downloadAndStore(firstFrame, "images");
            lastFrame = mediaStorageService.downloadAndStore(lastFrame, "images");

            StoryboardItem item = storyboardService.getItemById(itemId);
            JSONObject customData = StrUtil.isBlank(item.getCustomData())
                    ? JSONUtil.createObj()
                    : JSONUtil.parseObj(item.getCustomData());
            customData.set("firstFrameImageUrl", firstFrame);
            customData.set("lastFrameImageUrl", lastFrame);
            item.setCustomData(customData.toString());
            storyboardService.updateItem(item);

            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("storyboardItemId", itemId)
                    .set("firstFrameImageUrl", firstFrame)
                    .set("lastFrameImageUrl", lastFrame)
                    .toString();
        } catch (Exception e) {
            log.error("[update_storyboard_item_frames] 保存失败", e);
            return error(e.getMessage());
        }
    }

    private String error(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
