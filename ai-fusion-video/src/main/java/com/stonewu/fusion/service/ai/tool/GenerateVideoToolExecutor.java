package com.stonewu.fusion.service.ai.tool;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.generation.consumer.VideoGenerationConsumer;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategyRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * AI 生视频工具（generate_video）
 * <p>
 * 职责：解析参数 → 构建 VideoTask → 提交到队列并同步等待结果。
 * <p>
 * 排队、并发控制、策略路由等全部由 {@link VideoGenerationConsumer} 统一处理，
 * 本工具通过 {@link VideoGenerationConsumer#submitAndWait} 复用其完整流程。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GenerateVideoToolExecutor implements ToolExecutor {

    /** 模型类型常量：视频生成 */
    private static final int MODEL_TYPE_VIDEO = 3;

    /** 默认同步等待超时时间（2 小时，给批量镜头排队留足时间） */
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 2 * 60 * 60 * 1000L;

    private final AiModelService aiModelService;
    private final VideoGenerationService videoGenerationService;
    private final VideoGenerationConsumer videoGenerationConsumer;
    private final GenerationModelCapabilityService generationModelCapabilityService;
    private final VideoGenerationStrategyRouter videoGenerationStrategyRouter;

    @Value("${app.generation.video.agent-tool-wait-timeout-ms:7200000}")
    private long waitTimeoutMs = DEFAULT_WAIT_TIMEOUT_MS;

    @Override
    public String getToolName() {
        return "generate_video";
    }

    @Override
    public String getDisplayName() {
        return "AI 生成视频";
    }

    @Override
    public String getToolDescription() {
        return """
                生成AI视频。根据提示词和可选的参考图片生成视频片段。

                适用场景：
                1. 为分镜镜头生成视频：根据画面描述、运镜指令和参考首帧图生成视频
                2. 文本生成视频：纯文字描述生成视频
                3. 多模态参考生视频：传入角色/道具/场景的参考图片、参考视频、参考音频，提升画面中人物和物品的一致性

                重要提示：
                - 提示词应详细描述画面内容、运镜方式、环境氛围
                - 提供 firstFrameImageUrl 可大幅提升画面一致性
                - 提供 referenceImageUrls 可锁定角色形象、道具外观等（Seedance 2.0 多模态参考特性）
                - 提供 referenceVideoUrls 可参考视频的动作、运镜、特效等
                - 提供 referenceAudioUrls 可参考音色、音乐旋律、对话内容等
                - 使用多模态参考时，提示词中用`图片1`/`视频1`/`音频1`等指代对应的参考素材
                - 生成耗时较长（通常 1-5 分钟），请耐心等待
                - 如果你打算传首帧、尾帧、参考图、参考视频或参考音频，或不确定当前默认模型是否支持这些字段，请先调用 get_generation_model_capabilities
                
                %s
                """.formatted(describeCurrentModelCapability());
    }

    @Override
    public String getParametersSchema() {
            AiModel model = resolvePreferredModelOrNull();
            GenerationModelCapabilityService.VideoModelCapability capability = model != null
                ? generationModelCapabilityService.resolveVideoCapability(model)
                : null;

            String firstFrameDescription = capability != null && !capability.supportsFirstFrame()
                ? "当前默认模型不支持 firstFrameImageUrl，请不要传该字段"
                : "首帧参考图片URL（图生视频模式，强烈建议提供）";
            String lastFrameDescription = capability != null && !capability.supportsLastFrame()
                ? "当前默认模型不支持 lastFrameImageUrl，请不要传该字段"
                : "尾帧参考图片URL（可选）";
            String referenceImageDescription = capability != null && !capability.supportsReferenceImages()
                ? "当前默认模型不支持 referenceImageUrls，请不要传该字段"
                : "多模态参考图片URL列表，用于锁定角色形象、道具外观、场景参考等";
            String referenceVideoDescription = capability != null && !capability.supportsReferenceVideos()
                ? "当前默认模型不支持 referenceVideoUrls，请不要传该字段"
                : "参考视频URL列表，用于参考动作表现、运镜方式、特效风格等";
            String referenceAudioDescription = capability != null && !capability.supportsReferenceAudios()
                ? "当前默认模型不支持 referenceAudioUrls，请不要传该字段"
                : "参考音频URL列表，用于参考音色、音乐旋律、对话内容等";

            return JSONUtil.createObj()
                .set("type", "object")
                .set("properties", JSONUtil.createObj()
                    .set("prompt", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "视频生成提示词，描述画面内容和运镜方式"))
                    .set("firstFrameImageUrl", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", firstFrameDescription))
                    .set("lastFrameImageUrl", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", lastFrameDescription))
                    .set("referenceImageUrls", JSONUtil.createObj()
                        .set("type", "array")
                        .set("items", JSONUtil.createObj().set("type", "string"))
                        .set("description", referenceImageDescription))
                    .set("referenceVideoUrls", JSONUtil.createObj()
                        .set("type", "array")
                        .set("items", JSONUtil.createObj().set("type", "string"))
                        .set("description", referenceVideoDescription))
                    .set("referenceAudioUrls", JSONUtil.createObj()
                        .set("type", "array")
                        .set("items", JSONUtil.createObj().set("type", "string"))
                        .set("description", referenceAudioDescription))
                    .set("storyboardItemId", JSONUtil.createObj()
                        .set("type", "integer")
                        .set("description", "分镜镜头ID；批量分镜生成时必须传，用于幂等防重复提交远端视频任务"))
                    .set("ratio", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "画面比例，如 16:9、9:16、1:1（默认 16:9）"))
                    .set("duration", JSONUtil.createObj()
                        .set("type", "integer")
                        .set("description", "视频时长（秒），默认 5"))
                    .set("cameraFixed", JSONUtil.createObj()
                        .set("type", "boolean")
                        .set("description", "是否固定镜头（不做运动），默认 false")))
                .set("required", JSONUtil.parseArray("[\"prompt\"]"))
                .toString();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        String idempotencyCategory = null;
        Long userId = context != null ? context.getUserId() : null;
        Long modelId = null;
        VideoTask task = null;
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            String prompt = params.getStr("prompt");
            if (StrUtil.isBlank(prompt)) {
                return errorResult("缺少 prompt");
            }

            String firstFrameImageUrl = params.getStr("firstFrameImageUrl");
            String lastFrameImageUrl = params.getStr("lastFrameImageUrl");
            String ratio = params.getStr("ratio", "16:9");
            Integer duration = params.getInt("duration", 5);
            Boolean cameraFixed = params.getBool("cameraFixed", false);
            Long storyboardItemId = positiveLong(params.getLong("storyboardItemId"));

            // 解析多模态参考图片列表
            List<String> referenceImageUrlList = new ArrayList<>();
            cn.hutool.json.JSONArray refImagesArr = params.getJSONArray("referenceImageUrls");
            if (refImagesArr != null) {
                for (int i = 0; i < refImagesArr.size(); i++) {
                    String url = refImagesArr.getStr(i);
                    if (StrUtil.isNotBlank(url)) {
                        referenceImageUrlList.add(url);
                    }
                }
            }
            String referenceImageUrls = CollUtil.isEmpty(referenceImageUrlList)
                    ? null : JSONUtil.toJsonStr(referenceImageUrlList);

            // 解析参考视频列表
            String referenceVideoUrls = parseUrlArray(params, "referenceVideoUrls");

            // 解析参考音频列表
            String referenceAudioUrls = parseUrlArray(params, "referenceAudioUrls");

            // 确定生成模式
            String generateMode = StrUtil.isNotBlank(firstFrameImageUrl) ? "image2video" : "text2video";

            AiModel model = resolvePreferredModel();
            modelId = model.getId();
            idempotencyCategory = storyboardItemCategory(storyboardItemId);
            VideoTask existingTask = findExistingStoryboardVideoTask(idempotencyCategory, userId, modelId);
            if (existingTask != null) {
                return handleExistingVideoTask(existingTask, prompt, effectiveWaitTimeoutMsOrDefault());
            }

            // 构建生视频任务
            task = VideoTask.builder()
                    .prompt(prompt)
                    .generateMode(generateMode)
                    .firstFrameImageUrl(firstFrameImageUrl)
                    .lastFrameImageUrl(lastFrameImageUrl)
                    .referenceImageUrls(referenceImageUrls)
                    .referenceVideoUrls(referenceVideoUrls)
                    .referenceAudioUrls(referenceAudioUrls)
                    .ratio(ratio)
                    .duration(duration)
                    .cameraFixed(cameraFixed)
                    .modelId(model.getId())
                    .category(idempotencyCategory)
                    .count(1)
                    .userId(userId)
                    .build();

            generationModelCapabilityService.validateVideoTask(model, task);
            long effectiveWaitTimeoutMs = resolveWaitTimeoutMs();

            log.info("[generate_video] 提交生视频任务: prompt={}, mode={}, ratio={}, duration={}s, modelId={}, modelCode={}, waitTimeout={}ms, 首帧: {}, 参考图: {}张",
                    StrUtil.sub(prompt, 0, 80), generateMode, ratio, duration, model.getId(), model.getCode(),
                    effectiveWaitTimeoutMs,
                    firstFrameImageUrl != null ? "有" : "无",
                    referenceImageUrlList.size());

            // 提交到队列并同步等待结果
            VideoTask completed = videoGenerationConsumer.submitAndWait(task, effectiveWaitTimeoutMs);

            // 从完成的任务中获取生成的视频 URL
            List<VideoItem> items = videoGenerationService.listItems(completed.getId());
            VideoItem videoItem = items.stream()
                    .filter(item -> StrUtil.isNotBlank(item.getVideoUrl()))
                    .findFirst()
                    .orElse(null);

            if (videoItem == null) {
                return errorResult("生成完成但未获取到视频 URL");
            }

            log.info("[generate_video] 生成成功: videoUrl={}, coverUrl={}",
                    videoItem.getVideoUrl(), videoItem.getCoverUrl());

            return successResult(videoItem, prompt);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResult("生成任务被中断");
        } catch (Exception e) {
            log.error("[generate_video] 生成视频失败", e);
            VideoTask failedTask = task != null && task.getId() != null
                    ? task
                    : findExistingStoryboardVideoTask(idempotencyCategory, userId, modelId);
            if (failedTask != null && StrUtil.isNotBlank(idempotencyCategory)) {
                return nonRetryableExistingTaskResult(failedTask,
                        "生成失败，已阻止同一镜头重复创建远端视频任务: " + e.getMessage());
            }
            return errorResult("生成失败: " + e.getMessage());
        }
    }

    /**
     * 获取默认视频生成模型的 ID
     */
    private AiModel resolvePreferredModel() {
        AiModel defaultModel = aiModelService.getDefaultByType(MODEL_TYPE_VIDEO);
        if (isSupportedVideoModel(defaultModel)) {
            return defaultModel;
        }
        List<AiModel> videoModels = aiModelService.getListByType(MODEL_TYPE_VIDEO);
        for (AiModel model : videoModels) {
            if (isSupportedVideoModel(model)) {
                if (defaultModel != null && !Objects.equals(model.getId(), defaultModel.getId())) {
                    log.warn("[generate_video] 默认视频模型没有可用策略，已回退到支持的视频模型: defaultModelId={}, fallbackModelId={}",
                            defaultModel.getId(), model.getId());
                }
                return model;
            }
        }
        String unsupportedDefault = defaultModel != null
                ? " 当前默认视频模型 " + modelLabel(defaultModel) + " 的平台为 "
                        + generationModelCapabilityService.resolveModelPlatform(defaultModel) + "，没有对应的视频生成策略。"
                : "";
        throw new IllegalStateException("未配置可用的视频生成模型。" + unsupportedDefault
                + " 请在设置中配置 DashScope、火山引擎、NewAPI 或 Google Flow 等受支持的视频模型，并设为默认视频模型。"
                + " 当前已注册的视频策略: " + videoGenerationStrategyRouter.supportedPlatformsText());
    }

    private AiModel resolvePreferredModelOrNull() {
        try {
            return resolvePreferredModel();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String describeCurrentModelCapability() {
        AiModel model = resolvePreferredModelOrNull();
        return generationModelCapabilityService.describeVideoCapability(model);
    }

    private boolean isSupportedVideoModel(AiModel model) {
        return model != null && videoGenerationStrategyRouter.supports(model);
    }

    private String modelLabel(AiModel model) {
        if (model == null) {
            return "未命名模型";
        }
        return StrUtil.blankToDefault(model.getName(), model.getCode());
    }

    private long resolveWaitTimeoutMs() {
        return waitTimeoutMs > 0 ? waitTimeoutMs : DEFAULT_WAIT_TIMEOUT_MS;
    }

    private long effectiveWaitTimeoutMsOrDefault() {
        return resolveWaitTimeoutMs();
    }

    private String errorResult(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }

    private String successResult(VideoItem videoItem, String prompt) {
        return JSONUtil.createObj()
                .set("status", "success")
                .set("videoUrl", videoItem.getVideoUrl())
                .set("coverUrl", videoItem.getCoverUrl())
                .set("duration", videoItem.getDuration())
                .set("prompt", prompt)
                .toString();
    }

    private VideoTask findExistingStoryboardVideoTask(String category, Long userId, Long modelId) {
        if (StrUtil.isBlank(category)) {
            return null;
        }
        return videoGenerationService.findLatestByCategory(category, userId, modelId);
    }

    private String handleExistingVideoTask(VideoTask existingTask, String prompt, long timeoutMs)
            throws InterruptedException {
        if ((existingTask.getStatus() != null && existingTask.getStatus() == 0)
                || (existingTask.getStatus() != null && existingTask.getStatus() == 1)) {
            try {
                VideoTask completed = videoGenerationConsumer.waitForTask(existingTask.getTaskId(), timeoutMs);
                return resultFromCompletedTask(completed, prompt);
            } catch (RuntimeException e) {
                return nonRetryableExistingTaskResult(existingTask,
                        "同一镜头已有视频任务提交过远端，本次只等待已有任务，不再重复创建。等待结果失败: " + e.getMessage());
            }
        }
        if (existingTask.getStatus() != null && existingTask.getStatus() == 2) {
            return resultFromCompletedTask(existingTask, prompt);
        }
        return nonRetryableExistingTaskResult(existingTask,
                "同一镜头已有视频任务失败，已阻止重复创建远端视频任务。请修正参考图 URL 或手动清理任务后再提交。");
    }

    private String resultFromCompletedTask(VideoTask completedTask, String prompt) {
        List<VideoItem> items = videoGenerationService.listItems(completedTask.getId());
        VideoItem videoItem = firstVideoItem(items);
        if (videoItem == null) {
            return nonRetryableExistingTaskResult(completedTask,
                    "已有视频任务完成但未获取到视频 URL，已阻止重复创建远端视频任务。");
        }
        return successResult(videoItem, prompt);
    }

    private String nonRetryableExistingTaskResult(VideoTask task, String message) {
        return JSONUtil.createObj()
                .set("status", "error")
                .set("message", message)
                .set("retryable", false)
                .set("remoteTaskSubmitted", hasPlatformTaskId(task))
                .set("videoTaskId", task.getId())
                .set("taskId", task.getTaskId())
                .set("platformTaskIds", platformTaskIds(task))
                .toString();
    }

    private boolean hasPlatformTaskId(VideoTask task) {
        return !platformTaskIds(task).isEmpty();
    }

    private List<String> platformTaskIds(VideoTask task) {
        if (task == null || task.getId() == null) {
            return List.of();
        }
        List<VideoItem> items = videoGenerationService.listItems(task.getId());
        List<String> ids = new ArrayList<>();
        for (VideoItem item : items) {
            if (StrUtil.isNotBlank(item.getPlatformTaskId())) {
                ids.add(item.getPlatformTaskId());
            }
        }
        return ids;
    }

    private VideoItem firstVideoItem(List<VideoItem> items) {
        if (items == null) {
            return null;
        }
        return items.stream()
                .filter(item -> StrUtil.isNotBlank(item.getVideoUrl()))
                .findFirst()
                .orElse(null);
    }

    private Long positiveLong(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private String storyboardItemCategory(Long storyboardItemId) {
        return storyboardItemId != null ? "storyboard_item:" + storyboardItemId : null;
    }

    /**
     * 从参数中解析 URL 数组字段，返回 JSON 字符串或 null
     */
    private String parseUrlArray(JSONObject params, String fieldName) {
        cn.hutool.json.JSONArray arr = params.getJSONArray(fieldName);
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            String url = arr.getStr(i);
            if (StrUtil.isNotBlank(url)) {
                urls.add(url);
            }
        }
        return CollUtil.isEmpty(urls) ? null : JSONUtil.toJsonStr(urls);
    }
}
