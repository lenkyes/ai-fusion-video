package com.stonewu.fusion.controller.generation;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.common.PageParam;
import com.stonewu.fusion.common.PageResult;
import com.stonewu.fusion.controller.generation.vo.VideoTaskSubmitReqVO;
import com.stonewu.fusion.convert.generation.GenerationConvert;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.entity.generation.VideoGenerationSession;
import com.stonewu.fusion.mapper.generation.VideoGenerationSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.json.JSONObject;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.generation.consumer.VideoGenerationConsumer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.stonewu.fusion.security.SecurityUtils.requireCurrentUserId;

/**
 * 生视频 Controller
 */
@Tag(name = "视频生成")
@RestController
@RequestMapping("/api/generation/video")
@RequiredArgsConstructor
public class VideoGenerationController {

    private final VideoGenerationService videoGenerationService;
    private final VideoGenerationConsumer videoGenerationConsumer;
    private final VideoGenerationSessionMapper sessionMapper;
    private final AiModelService aiModelService;
    private final GenerationModelCapabilityService generationModelCapabilityService;

    @Operation(summary = "查询视频模型能力配置（时长/分辨率/宽高比等）")
    @GetMapping("/capability")
    public CommonResult<JSONObject> capability(@RequestParam(required = false) Long modelId) {
        AiModel model = null;
        if (modelId != null) {
            try {
                model = aiModelService.getById(modelId);
            } catch (Exception ignored) {
                // 模型不存在时回退到默认模型
            }
        }
        if (model == null) {
            model = aiModelService.getDefaultByType(3);
        }
        if (model == null) {
            List<AiModel> videoModels = aiModelService.getListByType(3);
            model = videoModels.isEmpty() ? null : videoModels.get(0);
        }
        return CommonResult.success(generationModelCapabilityService.buildVideoCapabilitySnapshot(model));
    }

    @GetMapping("/sessions")
    public CommonResult<List<VideoGenerationSession>> sessions() {
        return CommonResult.success(sessionMapper.selectList(new LambdaQueryWrapper<VideoGenerationSession>()
                .eq(VideoGenerationSession::getUserId, requireCurrentUserId())
                .orderByDesc(VideoGenerationSession::getUpdateTime)));
    }

    @PostMapping("/sessions")
    public CommonResult<Long> createSession(@RequestBody VideoGenerationSession session) {
        session.setId(null); session.setUserId(requireCurrentUserId());
        if (org.springframework.util.StringUtils.hasText(session.getTitle()) == false) session.setTitle("新会话");
        sessionMapper.insert(session); return CommonResult.success(session.getId());
    }

    @Operation(summary = "提交生视频任务")
    @PostMapping("/submit")
    public CommonResult<String> submit(@Valid @RequestBody VideoTaskSubmitReqVO reqVO) {
        VideoTask task = GenerationConvert.INSTANCE.convert(reqVO);
        task.setUserId(requireCurrentUserId());
        if (task.getSessionId() != null) {
            VideoGenerationSession session = sessionMapper.selectById(task.getSessionId());
            if (session == null || !requireCurrentUserId().equals(session.getUserId())) {
                throw new org.springframework.security.access.AccessDeniedException("无权使用该会话");
            }
        }
        String taskId = videoGenerationConsumer.submitTask(task);
        return CommonResult.success(taskId);
    }

    @Operation(summary = "查询生视频任务")
    @GetMapping("/{taskId}")
    public CommonResult<VideoTask> get(@PathVariable String taskId) {
        VideoTask task = videoGenerationService.getByTaskId(taskId);
        if (!requireCurrentUserId().equals(task.getUserId())) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该任务");
        }
        return CommonResult.success(task);
    }

    @Operation(summary = "查询生视频任务的视频条目")
    @GetMapping("/{id}/items")
    public CommonResult<List<VideoItem>> listItems(@PathVariable Long id) {
        VideoTask task = videoGenerationService.getById(id);
        if (!requireCurrentUserId().equals(task.getUserId())) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该任务");
        }
        return CommonResult.success(videoGenerationService.listItems(id));
    }

    @Operation(summary = "分页查询当前用户的生视频任务")
    @GetMapping("/page")
    public CommonResult<PageResult<VideoTask>> page(PageParam pageParam,
                                                    @RequestParam(required = false) String category,
                                                    @RequestParam(required = false) Long sessionId) {
        Long userId = requireCurrentUserId();
        PageResult<VideoTask> result = "dashboard_video_gen".equals(category)
                ? sessionId == null
                    ? videoGenerationService.pageByUserAndCategory(userId, category, pageParam.getPageNo(), pageParam.getPageSize())
                    : videoGenerationService.pageByUserCategoryAndSession(userId, category, sessionId,
                            pageParam.getPageNo(), pageParam.getPageSize())
                : videoGenerationService.pageByUser(userId, pageParam.getPageNo(), pageParam.getPageSize());
        return CommonResult.success(result);
    }
}
