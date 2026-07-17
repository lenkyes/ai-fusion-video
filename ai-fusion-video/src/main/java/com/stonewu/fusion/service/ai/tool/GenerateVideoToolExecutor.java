package com.stonewu.fusion.service.ai.tool;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.generation.consumer.VideoGenerationConsumer;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategyRouter;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import com.stonewu.fusion.service.system.SystemConfigService;
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
    private final SystemConfigService systemConfigService;
    private final StoryboardService storyboardService;
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
                    .set("generateAudio", JSONUtil.createObj()
                        .set("type", "boolean")
                        .set("description", "是否让支持的生视频模型生成原生配音、环境声和音效，默认 true；需要静音素材或后期另配音时传 false"))
                    .set("storyboardItemId", JSONUtil.createObj()
                        .set("type", "integer")
                        .set("description", "分镜镜头ID；批量分镜生成时必须传，用于幂等防重复提交远端视频任务"))
                    .set("projectId", JSONUtil.createObj()
                        .set("type", "integer")
                        .set("description", "项目ID，用于成本统计和生成任务归属；分镜生成时建议传"))
                    .set("forceRegenerate", JSONUtil.createObj()
                        .set("type", "boolean")
                        .set("description", "用户明确要求重新生成/覆盖已有失败或成功任务时传 true；同一轮失败自动重试不要传 true"))
                    .set("overwriteExistingVideo", JSONUtil.createObj()
                        .set("type", "boolean")
                        .set("description", "兼容字段，含义等同 forceRegenerate"))
                    .set("generationRequestId", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "本次用户提交的唯一请求ID；重新生成时传入，用于区分新请求和同一轮重复调用"))
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
        String storyboardCategory = null;
        Long userId = context != null ? context.getUserId() : null;
        Long modelId = null;
        VideoTask task = null;
        try {
            JSONObject params = JSONUtil.parseObj(toolInput);
            String prompt = params.getStr("prompt");
            if (StrUtil.isBlank(prompt)) {
                return errorResult("缺少 prompt");
            }

            String ratio = params.getStr("ratio", "16:9");
            Integer duration = params.getInt("duration", 5);
            Boolean cameraFixed = params.getBool("cameraFixed", false);
            Boolean generateAudio = params.getBool("generateAudio", true);
            Long storyboardItemId = positiveLong(params.getLong("storyboardItemId"));
            Long projectId = positiveLong(params.getLong("projectId"));
            if (projectId == null) {
                projectId = resolveProjectIdFromStoryboardItem(storyboardItemId);
            }
            boolean forceRegenerate = params.getBool("forceRegenerate",
                    params.getBool("overwriteExistingVideo", false));
            String generationRequestId = params.getStr("generationRequestId");

            AiModel model = resolvePreferredModel();
            modelId = model.getId();
            prompt = enhanceGrokImaginePrompt(prompt, model);
            storyboardCategory = storyboardItemCategory(storyboardItemId);
            idempotencyCategory = resolveIdempotencyCategory(storyboardCategory, forceRegenerate, generationRequestId);
            VideoTask existingTask = findActiveStoryboardVideoTask(storyboardCategory, userId, modelId);
            if (existingTask == null) {
                existingTask = findExistingStoryboardVideoTask(storyboardCategory, userId, modelId);
            }
            if (shouldUseExistingTask(existingTask, forceRegenerate, idempotencyCategory)) {
                return handleExistingVideoTask(existingTask, prompt, effectiveWaitTimeoutMsOrDefault());
            }
            if (existingTask != null && forceRegenerate) {
                log.info("[generate_video] 用户要求重新生成，忽略已有非运行中的历史分镜视频任务: storyboardItemId={}, existingTaskId={}, status={}, newCategory={}",
                        storyboardItemId, existingTask.getId(), existingTask.getStatus(), idempotencyCategory);
            }

            GenerationModelCapabilityService.VideoModelCapability capability =
                    generationModelCapabilityService.resolveVideoCapability(model);
            boolean grokImagine = isGrokImagineModel(model);

            String firstFrameImageUrl = params.getStr("firstFrameImageUrl");
            String lastFrameImageUrl = params.getStr("lastFrameImageUrl");
            PreviousShotInputs previousShotInputs = PreviousShotInputs.EMPTY;
            if (storyboardItemId != null) {
                StoryboardFrameInputs frameInputs = resolveStoryboardFrameInputs(storyboardItemId);
                previousShotInputs = resolvePreviousShotInputs(storyboardItemId);
                if (StrUtil.isNotBlank(previousShotInputs.videoUrl())
                        && StrUtil.isBlank(previousShotInputs.lastFrameImageUrl())) {
                    log.warn("[generate_video] 上一镜头存在视频但没有可用尾帧图，将退化为视频参考: storyboardItemId={}, previousItemId={}",
                            storyboardItemId, previousShotInputs.storyboardItemId());
                }
                if ((grokImagine || supportsFirstFrame(capability)) && StrUtil.isBlank(firstFrameImageUrl)) {
                    firstFrameImageUrl = firstNonBlank(
                            frameInputs.firstFrameImageUrl(),
                            previousShotInputs.lastFrameImageUrl());
                    if (StrUtil.isNotBlank(frameInputs.firstFrameImageUrl())) {
                        log.info("[generate_video] 自动使用分镜镜头预生成首帧图: storyboardItemId={}", storyboardItemId);
                    } else if (StrUtil.isNotBlank(previousShotInputs.lastFrameImageUrl())) {
                        log.info("[generate_video] 自动使用上一镜头尾帧作为首帧: storyboardItemId={}, previousItemId={}",
                                storyboardItemId, previousShotInputs.storyboardItemId());
                    }
                }
                if ((grokImagine || supportsLastFrame(capability)) && StrUtil.isBlank(lastFrameImageUrl)
                        && StrUtil.isNotBlank(frameInputs.lastFrameImageUrl())) {
                    lastFrameImageUrl = frameInputs.lastFrameImageUrl();
                    log.info("[generate_video] 自动使用分镜镜头尾帧图: storyboardItemId={}", storyboardItemId);
                }
            }

            firstFrameImageUrl = resolvePublicMediaUrl(firstFrameImageUrl, "firstFrameImageUrl");
            lastFrameImageUrl = resolvePublicMediaUrl(lastFrameImageUrl, "lastFrameImageUrl");

            // 解析多模态参考图片列表。预设画风图只参与文字风格，不作为视频主体参考图传给上游。
            List<String> referenceImageUrlList = new ArrayList<>(
                    collectMediaUrls(params, "referenceImageUrls", true));
            if (grokImagine) {
                List<String> orderedReferences = new ArrayList<>();
                addDistinct(orderedReferences, firstFrameImageUrl);
                addDistinct(orderedReferences, lastFrameImageUrl);
                for (String referenceImageUrl : referenceImageUrlList) {
                    addDistinct(orderedReferences, referenceImageUrl);
                }
                Integer maxReferenceImages = capability != null ? capability.maxReferenceImages() : null;
                if (maxReferenceImages != null && orderedReferences.size() > maxReferenceImages) {
                    log.warn("[generate_video] Grok reference images exceed limit; keeping the first {} images",
                            maxReferenceImages);
                    orderedReferences = new ArrayList<>(orderedReferences.subList(0, maxReferenceImages));
                }
                boolean hasStartReference = StrUtil.isNotBlank(firstFrameImageUrl);
                boolean hasEndReference = StrUtil.isNotBlank(lastFrameImageUrl)
                        && !Objects.equals(firstFrameImageUrl, lastFrameImageUrl);
                prompt = appendGrokFrameReferenceGuidance(prompt, hasStartReference, hasEndReference);
                referenceImageUrlList = orderedReferences;
                log.info("[generate_video] mapped Grok storyboard frames to ordered references: start={}, end={}, total={}",
                        hasStartReference, hasEndReference, referenceImageUrlList.size());
                firstFrameImageUrl = null;
                lastFrameImageUrl = null;
            }
            String referenceImageUrls = toJsonOrNull(referenceImageUrlList);

            // 解析参考视频列表
            List<String> referenceVideoUrlList = new ArrayList<>();
            if (capability == null || capability.supportsReferenceVideos()) {
                addDistinct(referenceVideoUrlList,
                        resolvePublicMediaUrl(previousShotInputs.videoUrl(), "previousShotVideoUrl"));
            }
            for (String url : collectMediaUrls(params, "referenceVideoUrls", false)) {
                addDistinct(referenceVideoUrlList, url);
            }
            String referenceVideoUrls = toJsonOrNull(referenceVideoUrlList);

            // 解析参考音频列表
            String referenceAudioUrls = toJsonOrNull(collectMediaUrls(params, "referenceAudioUrls", false));

            // 确定生成模式
            String generateMode = StrUtil.isNotBlank(firstFrameImageUrl) ? "image2video" : "text2video";

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
                    .generateAudio(generateAudio)
                    .modelId(model.getId())
                    .category(idempotencyCategory)
                    .count(1)
                    .userId(userId)
                    .projectId(projectId)
                    .build();

            generationModelCapabilityService.validateVideoTask(model, task);
            long effectiveWaitTimeoutMs = resolveWaitTimeoutMs();

            log.info("[generate_video] 提交生视频任务: prompt={}, mode={}, ratio={}, duration={}s, modelId={}, modelCode={}, waitTimeout={}ms, 首帧: {}, 尾帧: {}, 参考图: {}张",
                    StrUtil.sub(prompt, 0, 80), generateMode, ratio, duration, model.getId(), model.getCode(),
                    effectiveWaitTimeoutMs,
                    firstFrameImageUrl != null ? "有" : "无",
                    lastFrameImageUrl != null ? "有" : "无",
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

            persistStoryboardGenerationResult(storyboardItemId, videoItem);

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
                    : findExistingStoryboardVideoTask(
                            StrUtil.isNotBlank(storyboardCategory) ? storyboardCategory : idempotencyCategory,
                            userId,
                            modelId);
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
        String unsupportedDefault = defaultModel != null
                ? " 当前默认视频模型 " + modelLabel(defaultModel) + " 的平台为 "
                        + generationModelCapabilityService.resolveModelPlatform(defaultModel) + "，没有对应的视频生成策略。"
                : "";
        throw new IllegalStateException("未配置可用的视频生成模型。" + unsupportedDefault
                + " 请检查该默认模型的 API 平台、模型族或 videoStrategy 配置。"
                + " 当前已注册的视频策略: " + videoGenerationStrategyRouter.supportedPlatformsText());
    }

    /**
     * Grok Imagine is particularly sensitive to relative scale and contact points in
     * multi-person reference scenes. Keep this guidance close to the actual request
     * so it is applied to regeneration as well as the initial generation.
     */
    private String enhanceGrokImaginePrompt(String prompt, AiModel model) {
        if (!isGrokImagineModel(model)) {
            return prompt;
        }
        return prompt + "\n\nPhysical continuity requirements: preserve natural human anatomy and consistent body proportions for every person. In multi-person shots, keep all people at a believable relative scale according to camera distance and place each person in a clear, stable position without resizing or stretching anyone. Feet must stay on the floor or on the stated support; seated people must have hips supported by the chair/bench/ground with thighs and legs visibly connected, never floating. Maintain gravity, contact shadows, and correct occlusion. Do not merge bodies or limbs, and do not let any person appear disproportionately enlarged, elongated, or detached from the environment.";
    }

    private boolean isGrokImagineModel(AiModel model) {
        if (model == null) {
            return false;
        }
        return containsGrok(model.getModelFamily())
                || containsGrok(model.getCode())
                || containsGrok(model.getName());
    }

    private boolean containsGrok(String value) {
        return StrUtil.isNotBlank(value) && value.toLowerCase(java.util.Locale.ROOT).contains("grok");
    }

    private String appendGrokFrameReferenceGuidance(String prompt,
                                                     boolean hasStartReference,
                                                     boolean hasEndReference) {
        if (hasStartReference && hasEndReference) {
            return prompt + "\n\nReference image mapping: @image1 is the starting composition and character placement reference. @image2 is the desired ending state and composition reference. Create a natural, physically continuous transition from the first state toward the second; use @image2 as ending guidance rather than forcing an exact frame interpolation. Keep character identity, relative scale, body proportions, clothing, environment, and spatial layout consistent between both references.";
        }
        if (hasStartReference) {
            return prompt + "\n\nReference image mapping: @image1 is the starting composition and character placement reference. Preserve its character identity, relative scale, body proportions, environment, and spatial layout throughout the shot.";
        }
        if (hasEndReference) {
            return prompt + "\n\nReference image mapping: @image1 is the desired ending state and composition reference. Move naturally toward this state while preserving character identity, relative scale, body proportions, environment, and spatial layout.";
        }
        return prompt;
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
                .set("lastFrameUrl", videoItem.getLastFrameUrl())
                .set("duration", videoItem.getDuration())
                .set("prompt", prompt)
                .toString();
    }

    private VideoTask findExistingStoryboardVideoTask(String category, Long userId, Long modelId) {
        if (StrUtil.isBlank(category)) {
            return null;
        }
        return videoGenerationService.findLatestByCategoryFamily(category, userId, modelId);
    }

    private VideoTask findActiveStoryboardVideoTask(String category, Long userId, Long modelId) {
        if (StrUtil.isBlank(category)) {
            return null;
        }
        return videoGenerationService.findLatestActiveByCategoryFamily(category, userId, modelId);
    }

    private boolean shouldUseExistingTask(VideoTask existingTask, boolean forceRegenerate, String idempotencyCategory) {
        if (existingTask == null) {
            return false;
        }
        if (isActiveVideoTask(existingTask)) {
            return true;
        }
        if (forceRegenerate && isRequestScopedCategory(idempotencyCategory)
                && Objects.equals(existingTask.getCategory(), idempotencyCategory)) {
            return true;
        }
        return !forceRegenerate;
    }

    private boolean isActiveVideoTask(VideoTask task) {
        return task != null && task.getStatus() != null && (task.getStatus() == 0 || task.getStatus() == 1);
    }

    private String handleExistingVideoTask(VideoTask existingTask, String prompt, long timeoutMs)
            throws InterruptedException {
        if (isActiveVideoTask(existingTask)) {
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

    private Long resolveProjectIdFromStoryboardItem(Long storyboardItemId) {
        if (storyboardItemId == null) {
            return null;
        }
        try {
            StoryboardItem item = storyboardService.getItemById(storyboardItemId);
            if (item == null || item.getStoryboardId() == null) {
                return null;
            }
            Storyboard storyboard = storyboardService.getById(item.getStoryboardId());
            return storyboard != null ? storyboard.getProjectId() : null;
        } catch (Exception e) {
            log.warn("[generate_video] 读取分镜项目ID失败: storyboardItemId={}, reason={}",
                    storyboardItemId, e.getMessage());
            return null;
        }
    }

    private String resolveIdempotencyCategory(String storyboardCategory, boolean forceRegenerate,
            String generationRequestId) {
        if (StrUtil.isBlank(storyboardCategory)) {
            return null;
        }
        String requestScope = generationRequestScope(generationRequestId);
        if (forceRegenerate && StrUtil.isNotBlank(requestScope)) {
            return storyboardCategory + ":request:" + requestScope;
        }
        return storyboardCategory;
    }

    private String generationRequestScope(String requestId) {
        if (StrUtil.isBlank(requestId)) {
            return null;
        }
        return "req_" + DigestUtil.sha256Hex(requestId.trim()).substring(0, 16);
    }

    private boolean isRequestScopedCategory(String category) {
        return StrUtil.isNotBlank(category) && category.contains(":request:");
    }

    private StoryboardFrameInputs resolveStoryboardFrameInputs(Long storyboardItemId) {
        if (storyboardItemId == null) {
            return StoryboardFrameInputs.EMPTY;
        }
        try {
            StoryboardItem item = storyboardService.getItemById(storyboardItemId);
            if (item == null) {
                return StoryboardFrameInputs.EMPTY;
            }
            String firstFrameImageUrl = firstNonBlank(
                    item.getGeneratedImageUrl(),
                    item.getImageUrl(),
                    item.getReferenceImageUrl());
            String lastFrameImageUrl = extractLastFrameImageUrl(item.getCustomData());
            return new StoryboardFrameInputs(firstFrameImageUrl, lastFrameImageUrl);
        } catch (Exception e) {
            log.warn("[generate_video] 读取分镜首尾帧失败: storyboardItemId={}, reason={}",
                    storyboardItemId, e.getMessage());
            return StoryboardFrameInputs.EMPTY;
        }
    }

    private PreviousShotInputs resolvePreviousShotInputs(Long storyboardItemId) {
        try {
            StoryboardItem current = storyboardService.getItemById(storyboardItemId);
            if (current == null || current.getStoryboardId() == null) {
                return PreviousShotInputs.EMPTY;
            }
            List<StoryboardItem> items = storyboardService.listItems(current.getStoryboardId());
            for (int i = 1; i < items.size(); i++) {
                if (!Objects.equals(items.get(i).getId(), storyboardItemId)) {
                    continue;
                }
                StoryboardItem previous = items.get(i - 1);
                return new PreviousShotInputs(
                        previous.getId(),
                        extractLastFrameImageUrl(previous.getCustomData()),
                        firstNonBlank(previous.getGeneratedVideoUrl(), previous.getVideoUrl()));
            }
        } catch (Exception e) {
            log.warn("[generate_video] 读取上一分镜参考失败: storyboardItemId={}, reason={}",
                    storyboardItemId, e.getMessage());
        }
        return PreviousShotInputs.EMPTY;
    }

    private void persistStoryboardGenerationResult(Long storyboardItemId, VideoItem videoItem) {
        if (storyboardItemId == null || videoItem == null) {
            return;
        }
        try {
            StoryboardItem item = storyboardService.getItemById(storyboardItemId);
            if (item == null) {
                return;
            }
            item.setGeneratedVideoUrl(videoItem.getVideoUrl());
            storyboardService.updateItem(item);
        } catch (Exception e) {
            log.warn("[generate_video] 保存分镜视频连续性信息失败: storyboardItemId={}, reason={}",
                    storyboardItemId, e.getMessage());
        }
    }

    private void addDistinct(List<String> urls, String url) {
        if (StrUtil.isNotBlank(url) && !urls.contains(url)) {
            urls.add(url);
        }
    }

    private String extractLastFrameImageUrl(String customData) {
        if (StrUtil.isBlank(customData)) {
            return null;
        }
        try {
            JSONObject data = JSONUtil.parseObj(customData);
            return firstNonBlank(
                    data.getStr("lastFrameImageUrl"),
                    data.getStr("endFrameImageUrl"),
                    data.getStr("tailFrameImageUrl"),
                    data.getStr("lastFrameUrl"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean supportsFirstFrame(GenerationModelCapabilityService.VideoModelCapability capability) {
        return capability == null || capability.supportsFirstFrame();
    }

    private boolean supportsLastFrame(GenerationModelCapabilityService.VideoModelCapability capability) {
        return capability == null || capability.supportsLastFrame();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private List<String> collectMediaUrls(JSONObject params, String fieldName, boolean skipPresetArtStyles) {
        cn.hutool.json.JSONArray arr = params.getJSONArray(fieldName);
        if (arr == null || arr.isEmpty()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            String rawUrl = arr.getStr(i);
            if (StrUtil.isBlank(rawUrl)) {
                continue;
            }
            if (skipPresetArtStyles && isPresetArtStyleUrl(rawUrl)) {
                log.info("[generate_video] 已忽略预设画风参考图，避免视频模型误当主体参考: {}", rawUrl);
                continue;
            }
            String publicUrl = resolvePublicMediaUrl(rawUrl, fieldName);
            if (StrUtil.isNotBlank(publicUrl) && !urls.contains(publicUrl)) {
                urls.add(publicUrl);
            }
        }
        return urls;
    }

    private String toJsonOrNull(List<String> urls) {
        return CollUtil.isEmpty(urls) ? null : JSONUtil.toJsonStr(urls);
    }

    private String resolvePublicMediaUrl(String url, String fieldName) {
        String rawUrl = StrUtil.trim(url);
        if (StrUtil.isBlank(rawUrl)) {
            return null;
        }
        String publicUrl = systemConfigService.resolvePublicUrl(rawUrl);
        if (StrUtil.isBlank(publicUrl)) {
            throw new IllegalArgumentException(fieldName + " 包含相对资源路径 " + rawUrl
                    + "，但系统未配置资源公网域名 asset_public_base_url 或项目访问域名 site_base_url");
        }
        if (!isHttpUrl(publicUrl)) {
            throw new IllegalArgumentException(fieldName + " 必须是上游视频服务可访问的完整 http/https URL，当前为: " + publicUrl);
        }
        return publicUrl;
    }

    private boolean isHttpUrl(String url) {
        return StrUtil.startWithIgnoreCase(url, "http://")
                || StrUtil.startWithIgnoreCase(url, "https://");
    }

    private boolean isPresetArtStyleUrl(String url) {
        String value = StrUtil.trim(url);
        if (StrUtil.isBlank(value)) {
            return false;
        }
        String lower = value.toLowerCase();
        int queryIndex = lower.indexOf('?');
        if (queryIndex >= 0) {
            lower = lower.substring(0, queryIndex);
        }
        int fragmentIndex = lower.indexOf('#');
        if (fragmentIndex >= 0) {
            lower = lower.substring(0, fragmentIndex);
        }
        if (isHttpUrl(lower)) {
            int schemeIndex = lower.indexOf("://");
            int pathStart = schemeIndex >= 0 ? lower.indexOf('/', schemeIndex + 3) : -1;
            lower = pathStart >= 0 ? lower.substring(pathStart) : "/";
        }
        return lower.startsWith("/api/art-styles/") || lower.startsWith("/art-styles/");
    }

    private record StoryboardFrameInputs(String firstFrameImageUrl, String lastFrameImageUrl) {
        private static final StoryboardFrameInputs EMPTY = new StoryboardFrameInputs(null, null);
    }

    private record PreviousShotInputs(Long storyboardItemId, String lastFrameImageUrl, String videoUrl) {
        private static final PreviousShotInputs EMPTY = new PreviousShotInputs(null, null, null);
    }
}
