package com.stonewu.fusion.service.ai.tool;

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
 * Scores storyboard video candidates and optionally selects the best one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InspectStoryboardVideoQualityToolExecutor implements ToolExecutor {

    private final StoryboardVideoQualityService storyboardVideoQualityService;

    @Override
    public String getToolName() {
        return "inspect_storyboard_video_quality";
    }

    @Override
    public String getDisplayName() {
        return "分镜视频质检评分";
    }

    @Override
    public String getToolDescription() {
        return """
                对单个分镜镜头的所有视频候选版本进行角色一致性、画面质量、时长连续性、对白/声音准备度评分。
                可在评分后自动选择最高分候选，并回填到分镜镜头的 generatedVideoUrl。
                """;
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
                    "autoSelect": {
                      "type": "boolean",
                      "description": "是否自动选中最高分候选，默认 false"
                    },
                    "minScore": {
                      "type": "integer",
                      "description": "自动选中的最低总分，默认 75，范围 0-100"
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
            if (storyboardItemId == null || storyboardItemId <= 0) {
                return errorResult("缺少 storyboardItemId");
            }
            boolean autoSelect = params.getBool("autoSelect", false);
            Integer minScore = params.getInt("minScore");
            Long userId = context != null ? context.getUserId() : null;

            StoryboardVideoQualityService.QualityReviewResult result =
                    storyboardVideoQualityService.evaluateStoryboardItem(storyboardItemId, userId, autoSelect, minScore);
            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("storyboardItemId", result.storyboardItemId())
                    .set("candidateCount", result.candidates().size())
                    .set("selectedCandidateId", result.selectedCandidate() != null ? result.selectedCandidate().getId() : null)
                    .set("selectedVideoUrl", result.selectedCandidate() != null ? result.selectedCandidate().getVideoUrl() : null)
                    .set("message", result.message())
                    .set("candidates", result.candidates())
                    .toString();
        } catch (Exception e) {
            log.error("[inspect_storyboard_video_quality] failed", e);
            return errorResult("质检失败: " + e.getMessage());
        }
    }

    private String errorResult(String message) {
        return JSONUtil.createObj()
                .set("status", "error")
                .set("message", message)
                .toString();
    }
}
