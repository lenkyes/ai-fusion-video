package com.stonewu.fusion.service.ai.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.storyboard.StoryboardVideoQuality;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.storyboard.StoryboardVideoQualityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Selects a reviewed storyboard video candidate.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SelectStoryboardVideoCandidateToolExecutor implements ToolExecutor {

    private final StoryboardVideoQualityService storyboardVideoQualityService;

    @Override
    public String getToolName() {
        return "select_storyboard_video_candidate";
    }

    @Override
    public String getDisplayName() {
        return "选用分镜视频候选";
    }

    @Override
    public String getToolDescription() {
        return "选用某个已评分的视频候选版本，并回填到分镜镜头。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "storyboardItemId": {
                      "type": "integer",
                      "description": "分镜镜头 ID"
                    },
                    "qualityId": {
                      "type": "integer",
                      "description": "质检候选记录 ID。优先使用该字段"
                    },
                    "videoUrl": {
                      "type": "string",
                      "description": "候选视频 URL。未提供 qualityId 时可用该字段选择"
                    }
                  },
                  "required": ["storyboardItemId"],
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
            Long storyboardItemId = params.getLong("storyboardItemId");
            Long qualityId = params.getLong("qualityId");
            String videoUrl = params.getStr("videoUrl");
            if (storyboardItemId == null || storyboardItemId <= 0) {
                return errorResult("缺少 storyboardItemId");
            }

            StoryboardVideoQuality selected;
            if (qualityId != null && qualityId > 0) {
                selected = storyboardVideoQualityService.selectCandidate(storyboardItemId, qualityId);
            } else if (StrUtil.isNotBlank(videoUrl)) {
                selected = storyboardVideoQualityService.selectCandidateByVideoUrl(storyboardItemId, videoUrl);
            } else {
                return errorResult("qualityId 和 videoUrl 至少需要传入一个");
            }

            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("storyboardItemId", storyboardItemId)
                    .set("qualityId", selected.getId())
                    .set("videoUrl", selected.getVideoUrl())
                    .set("score", selected.getTotalScore())
                    .set("message", "已选用视频候选版本")
                    .toString();
        } catch (Exception e) {
            log.error("[select_storyboard_video_candidate] failed", e);
            return errorResult("选用失败: " + e.getMessage());
        }
    }

    private String errorResult(String message) {
        return JSONUtil.createObj()
                .set("status", "error")
                .set("message", message)
                .toString();
    }
}
