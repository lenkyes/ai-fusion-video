package com.stonewu.fusion.controller.generation;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.common.PageParam;
import com.stonewu.fusion.common.PageResult;
import com.stonewu.fusion.controller.generation.vo.ImageTaskSubmitReqVO;
import com.stonewu.fusion.convert.generation.GenerationConvert;
import com.stonewu.fusion.entity.generation.ImageItem;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.entity.generation.ImageGenerationSession;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.mapper.generation.ImageGenerationSessionMapper;
import cn.hutool.json.JSONObject;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.fusion.service.generation.ImageGenerationService;
import com.stonewu.fusion.service.generation.consumer.ImageGenerationConsumer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.stonewu.fusion.security.SecurityUtils.requireCurrentUserId;

/**
 * 生图 Controller
 */
@Tag(name = "图片生成")
@RestController
@RequestMapping("/api/generation/image")
@RequiredArgsConstructor
public class ImageGenerationController {

    private final ImageGenerationService imageGenerationService;
    private final ImageGenerationConsumer imageGenerationConsumer;
    private final ImageGenerationSessionMapper sessionMapper;
    private final AiModelService aiModelService;
    private final GenerationModelCapabilityService generationModelCapabilityService;

    @Operation(summary = "查询图片模型能力配置（尺寸/宽高比等）")
    @GetMapping("/capability")
    public CommonResult<JSONObject> capability(@RequestParam(required = false) Long modelId) {
        AiModel model = modelId != null ? aiModelService.getById(modelId) : null;
        if (model == null) {
            model = aiModelService.getDefaultByType(2);
        }
        if (model == null) {
            List<AiModel> imageModels = aiModelService.getListByType(2);
            model = imageModels.isEmpty() ? null : imageModels.get(0);
        }
        return CommonResult.success(generationModelCapabilityService.buildImageCapabilitySnapshot(model));
    }

    @GetMapping("/sessions")
    public CommonResult<List<ImageGenerationSession>> sessions() {
        return CommonResult.success(sessionMapper.selectList(new LambdaQueryWrapper<ImageGenerationSession>()
                .eq(ImageGenerationSession::getUserId, requireCurrentUserId())
                .orderByDesc(ImageGenerationSession::getUpdateTime)));
    }

    @PostMapping("/sessions")
    public CommonResult<Long> createSession(@RequestBody ImageGenerationSession session) {
        session.setId(null); session.setUserId(requireCurrentUserId());
        if (org.springframework.util.StringUtils.hasText(session.getTitle()) == false) session.setTitle("新会话");
        sessionMapper.insert(session); return CommonResult.success(session.getId());
    }

    @Operation(summary = "提交生图任务")
    @PostMapping("/submit")
    public CommonResult<String> submit(@Valid @RequestBody ImageTaskSubmitReqVO reqVO) {
        ImageTask task = GenerationConvert.INSTANCE.convert(reqVO);
        task.setUserId(requireCurrentUserId());
        if (task.getSessionId() != null) {
            ImageGenerationSession session = sessionMapper.selectById(task.getSessionId());
            if (session == null || !requireCurrentUserId().equals(session.getUserId())) {
                throw new org.springframework.security.access.AccessDeniedException("无权使用该会话");
            }
        }
        String taskId = imageGenerationConsumer.submitTask(task);
        return CommonResult.success(taskId);
    }

    @Operation(summary = "查询生图任务")
    @GetMapping("/{taskId}")
    public CommonResult<ImageTask> get(@PathVariable String taskId) {
        ImageTask task = imageGenerationService.getByTaskId(taskId);
        if (!requireCurrentUserId().equals(task.getUserId())) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该任务");
        }
        return CommonResult.success(task);
    }

    @Operation(summary = "查询生图任务的图片条目")
    @GetMapping("/{id}/items")
    public CommonResult<List<ImageItem>> listItems(@PathVariable Long id) {
        ImageTask task = imageGenerationService.getById(id);
        if (!requireCurrentUserId().equals(task.getUserId())) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问该任务");
        }
        return CommonResult.success(imageGenerationService.listItems(id));
    }

    @Operation(summary = "分页查询当前用户的生图任务")
    @GetMapping("/page")
    public CommonResult<PageResult<ImageTask>> page(PageParam pageParam,
                                                    @RequestParam(required = false) String category,
                                                    @RequestParam(required = false) Long sessionId) {
        Long userId = requireCurrentUserId();
        PageResult<ImageTask> result = "dashboard_image_gen".equals(category)
                ? sessionId == null
                    ? imageGenerationService.pageByUserAndCategory(userId, category, pageParam.getPageNo(), pageParam.getPageSize())
                    : imageGenerationService.pageByUserCategoryAndSession(userId, category, sessionId,
                            pageParam.getPageNo(), pageParam.getPageSize())
                : imageGenerationService.pageByUser(userId, pageParam.getPageNo(), pageParam.getPageSize());
        return CommonResult.success(result);
    }
}
