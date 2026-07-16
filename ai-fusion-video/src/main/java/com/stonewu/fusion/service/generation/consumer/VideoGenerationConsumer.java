package com.stonewu.fusion.service.generation.consumer;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.infrastructure.queue.RedisTaskQueue;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategy;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategyRouter;
import com.stonewu.fusion.service.storage.MediaStorageService;
import com.stonewu.fusion.service.system.SystemConfigService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生视频任务消费器
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoGenerationConsumer {

    private static final String BASE_QUEUE_NAME = "video_generation";
    private static final String MODEL_QUEUE_PREFIX = BASE_QUEUE_NAME + ":model:";
    private static final int MODEL_TYPE_VIDEO = 3;
    private static final int RUNNING_LEASE_MINUTES = 60;
    private static final int RECOVERY_STALE_MINUTES = 90;

    private final RedisTaskQueue taskQueue;
    private final VideoGenerationService videoGenerationService;
    private final AiModelService aiModelService;
    private final GenerationModelCapabilityService generationModelCapabilityService;
    private final VideoGenerationStrategyRouter videoGenerationStrategyRouter;
    private final MediaStorageService mediaStorageService;
    private final SystemConfigService systemConfigService;

    private final AtomicInteger workerThreadCounter = new AtomicInteger(1);
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "video-generation-worker-" + workerThreadCounter.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService leaseRenewExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "video-generation-lease-renewer");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 提交生视频任务到队列
     */
    public String submitTask(VideoTask task) {
        AiModel queueModel = resolveQueueModel(task.getModelId());
        if (queueModel == null) {
            throw new BusinessException("没有可用的视频生成模型");
        }
        task.setModelId(queueModel.getId());
        applyTaskDefaults(task, queueModel);
        normalizeTaskMediaUrls(task);

        String queueName = resolveQueueName(task.getModelId());
        String taskId = IdUtil.fastSimpleUUID();
        task.setTaskId(taskId);
        task.setStatus(0);
        videoGenerationService.create(task);

        refreshQueueMaxConcurrent(queueName, task.getModelId());

        for (int i = 0; i < task.getCount(); i++) {
            VideoItem item = VideoItem.builder()
                    .taskId(task.getId())
                    .status(0)
                    .build();
            videoGenerationService.createItem(item);
        }

        taskQueue.push(queueName, taskId);
        log.info("[VideoConsumer] 任务入队: taskId={}, queue={}, modelId={}", taskId, queueName, task.getModelId());
        return taskId;
    }

    public String retryTask(Long id, Long userId) {
        VideoTask existing = videoGenerationService.getById(id);
        if (userId != null && !userId.equals(existing.getUserId())) {
            throw new BusinessException(403, "无权重试该视频生成任务");
        }
        if (existing.getStatus() == null || existing.getStatus() != 3) {
            throw new BusinessException("仅失败任务可重试");
        }

        VideoTask task = videoGenerationService.resetForRetry(id);
        String queueName = resolveQueueName(task.getModelId());
        refreshQueueMaxConcurrent(queueName, task.getModelId());
        taskQueue.push(queueName, task.getTaskId());
        log.info("[VideoConsumer] 失败任务已重新入队: taskId={}, queue={}", task.getTaskId(), queueName);
        return task.getTaskId();
    }

    public int recoverExpiredRunningTasks(Long userId) {
        LocalDateTime before = LocalDateTime.now().minusMinutes(RECOVERY_STALE_MINUTES);
        int recovered = 0;
        for (VideoTask task : videoGenerationService.findRunningBefore(before, userId)) {
            if (task.getModelId() == null || StrUtil.isBlank(task.getTaskId())) {
                continue;
            }
            String queueName = resolveQueueName(task.getModelId());
            if (taskQueue.isRunning(queueName, task.getTaskId())) {
                continue;
            }
            videoGenerationService.markRecoveredToQueued(task.getId(), "运行租约已失效，已自动恢复排队");
            refreshQueueMaxConcurrent(queueName, task.getModelId());
            taskQueue.push(queueName, task.getTaskId());
            recovered++;
            log.warn("[VideoConsumer] 发现卡住的视频任务，已恢复排队: taskId={}, queue={}", task.getTaskId(), queueName);
        }
        return recovered;
    }

    private void applyTaskDefaults(VideoTask task, AiModel model) {
        if (task.getWatermark() == null) {
            task.setWatermark(false);
        }
        if (task.getGenerateAudio() == null) {
            task.setGenerateAudio(true);
        }
    }

    private void normalizeTaskMediaUrls(VideoTask task) {
        task.setFirstFrameImageUrl(resolvePublicMediaUrl(task.getFirstFrameImageUrl(), "firstFrameImageUrl"));
        task.setLastFrameImageUrl(resolvePublicMediaUrl(task.getLastFrameImageUrl(), "lastFrameImageUrl"));
        task.setReferenceImageUrls(toJsonOrNull(collectPublicMediaUrls(task.getReferenceImageUrls(), true, "referenceImageUrls")));
        task.setReferenceVideoUrls(toJsonOrNull(collectPublicMediaUrls(task.getReferenceVideoUrls(), false, "referenceVideoUrls")));
        task.setReferenceAudioUrls(toJsonOrNull(collectPublicMediaUrls(task.getReferenceAudioUrls(), false, "referenceAudioUrls")));
    }

    private List<String> collectPublicMediaUrls(String rawUrls, boolean skipPresetArtStyles, String fieldName) {
        List<String> rawList = parseMediaUrlList(rawUrls, fieldName);
        if (rawList.isEmpty()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (String rawUrl : rawList) {
            if (skipPresetArtStyles && isPresetArtStyleUrl(rawUrl)) {
                log.info("[VideoConsumer] Skip preset art-style reference image: {}", rawUrl);
                continue;
            }
            String publicUrl = resolvePublicMediaUrl(rawUrl, fieldName);
            if (StrUtil.isNotBlank(publicUrl) && !urls.contains(publicUrl)) {
                urls.add(publicUrl);
            }
        }
        return urls;
    }

    private List<String> parseMediaUrlList(String rawUrls, String fieldName) {
        String trimmed = StrUtil.trim(rawUrls);
        if (StrUtil.isBlank(trimmed)) {
            return List.of();
        }
        if (!trimmed.startsWith("[")) {
            return List.of(trimmed);
        }
        try {
            JSONArray array = JSONUtil.parseArray(trimmed);
            List<String> urls = new ArrayList<>();
            for (Object item : array) {
                String url = item == null ? null : StrUtil.trim(item.toString());
                if (StrUtil.isNotBlank(url)) {
                    urls.add(url);
                }
            }
            return urls;
        } catch (Exception e) {
            throw new BusinessException("Failed to parse " + fieldName + ": " + e.getMessage());
        }
    }

    private String toJsonOrNull(List<String> urls) {
        return urls == null || urls.isEmpty() ? null : JSONUtil.toJsonStr(urls);
    }

    private String resolvePublicMediaUrl(String url, String fieldName) {
        String rawUrl = StrUtil.trim(url);
        if (StrUtil.isBlank(rawUrl)) {
            return null;
        }
        String publicUrl = systemConfigService.resolvePublicUrl(rawUrl);
        if (StrUtil.isBlank(publicUrl)) {
            throw new BusinessException(fieldName + " contains relative media URL " + rawUrl
                    + ", but asset_public_base_url or site_base_url is not configured");
        }
        if (!isHttpUrl(publicUrl)) {
            throw new BusinessException(fieldName + " must be a public http/https URL, current value: " + publicUrl);
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
        String lower = value.toLowerCase(Locale.ROOT);
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

    /**
     * 提交任务并同步等待结果（阻塞当前线程）
     * <p>
     * 适用于 AI Agent 工具等需要同步获取生视频结果的场景。
     * 内部流程：submitTask() 入队 → Consumer 定时取出执行 → 本方法轮询 DB 等待完成。
     *
     * @param task      生视频任务（需设置好 prompt、generateMode、modelId 等）
     * @param timeoutMs 最大等待时间（毫秒）
     * @return 完成的 VideoTask（含结果视频在 VideoItem 中）
     * @throws RuntimeException 超时或任务失败时抛出
     * @throws InterruptedException 等待过程中线程被中断
     */
    public VideoTask submitAndWait(VideoTask task, long timeoutMs) throws InterruptedException {
        String taskId = submitTask(task);
        return waitForTask(taskId, timeoutMs);
    }

    public VideoTask waitForTask(String taskId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long pollInterval = 3000L;

        log.info("[VideoConsumer] 同步等待任务完成: taskId={}, timeout={}ms", taskId, timeoutMs);

        while (System.currentTimeMillis() < deadline) {
            VideoTask current = videoGenerationService.getByTaskId(taskId);
            switch (current.getStatus()) {
                case 2: // 已完成
                    log.info("[VideoConsumer] 任务已完成: taskId={}", taskId);
                    return current;
                case 3: // 失败
                    String errorMsg = current.getErrorMsg() != null ? current.getErrorMsg() : "未知错误";
                    throw new RuntimeException("生视频任务失败: " + errorMsg);
                default:
                    // 0-排队中 1-处理中，继续等待
                    break;
            }

            Thread.sleep(pollInterval);
        }

        // 超时：标记任务失败
        VideoTask current = videoGenerationService.getByTaskId(taskId);
        videoGenerationService.updateStatus(current.getId(), 3, "同步等待超时");
        throw new RuntimeException("生视频任务排队超时（等待 " + (timeoutMs / 1000) + " 秒），当前任务较多，请稍后重试");
    }

    @Scheduled(fixedDelay = 5000)
    public void consume() {
        for (String queueName : collectQueueNamesToConsume()) {
            drainQueue(queueName);
        }
    }

    private void drainQueue(String queueName) {
        while (true) {
            String taskId = taskQueue.acquireAndPop(queueName, 1);
            if (taskId == null) {
                return;
            }
            dispatchTask(queueName, taskId);
        }
    }

    private void dispatchTask(String queueName, String taskId) {
        workerExecutor.execute(() -> {
            ScheduledFuture<?> leaseRenewal = null;
            try {
                taskQueue.markRunning(queueName, taskId, RUNNING_LEASE_MINUTES);
                leaseRenewal = leaseRenewExecutor.scheduleAtFixedRate(
                        () -> renewLeaseQuietly(queueName, taskId, RUNNING_LEASE_MINUTES),
                        Math.max(1, RUNNING_LEASE_MINUTES / 3),
                        Math.max(1, RUNNING_LEASE_MINUTES / 3),
                        TimeUnit.MINUTES);
                processTask(queueName, taskId);
            } catch (Exception e) {
                log.error("[VideoConsumer] 任务处理失败: taskId={}", taskId, e);
            } finally {
                if (leaseRenewal != null) {
                    leaseRenewal.cancel(false);
                }
                taskQueue.markComplete(queueName, taskId);
                taskQueue.release(queueName);
            }
        });
    }

    private void renewLeaseQuietly(String queueName, String taskId, int timeoutMinutes) {
        try {
            boolean renewed = taskQueue.renewLease(queueName, taskId, timeoutMinutes);
            if (!renewed) {
                log.warn("[VideoConsumer] 任务运行租约续期失败: taskId={}, queue={}", taskId, queueName);
            }
        } catch (Exception e) {
            log.warn("[VideoConsumer] 任务运行租约续期异常: taskId={}, queue={}", taskId, queueName, e);
        }
    }

    @Scheduled(fixedDelayString = "${app.generation.video.recovery-scan-delay-ms:60000}")
    public void recoverExpiredRunningTasks() {
        int recovered = recoverExpiredRunningTasks(null);
        if (recovered > 0) {
            log.warn("[VideoConsumer] 已自动恢复视频生成任务数量: {}", recovered);
        }
    }

    private void processTask(String queueName, String taskId) {
        VideoTask task;
        try {
            task = videoGenerationService.getByTaskId(taskId);
        } catch (Exception e) {
            log.error("[VideoConsumer] 任务不存在: taskId={}", taskId);
            return;
        }

        refreshQueueMaxConcurrent(queueName, task.getModelId());
        videoGenerationService.updateStatus(task.getId(), 1, null);

        AiModel model = null;
        if (task.getModelId() != null) {
            try {
                model = aiModelService.getById(task.getModelId());
            } catch (Exception e) {
                log.warn("[VideoConsumer] 模型配置获取失败: modelId={}", task.getModelId());
            }
        }
        if (model == null) {
            videoGenerationService.updateStatus(task.getId(), 3, "视频模型不存在或已禁用");
            return;
        }
        if (!videoGenerationStrategyRouter.supports(model)) {
            String platform;
            try {
                platform = generationModelCapabilityService.resolveModelPlatform(model);
            } catch (Exception e) {
                platform = "未知平台";
            }
            videoGenerationService.updateStatus(task.getId(), 3,
                    "当前视频模型平台 " + platform + " 没有可用的视频生成策略，请切换到支持的视频模型");
            return;
        }

        try {
            VideoGenerationStrategy strategy = videoGenerationStrategyRouter.resolve(model);
            generationModelCapabilityService.validateVideoTask(model, task);
            String platformTaskId = strategy.submit(task);
            log.info("[VideoConsumer] 任务已提交到平台: taskId={}, platformTaskId={}", taskId, platformTaskId);
            strategy.poll(platformTaskId, task);

            // 持久化远程视频文件到本地/OSS 存储
            persistVideoItems(task);

            videoGenerationService.updateStatus(task.getId(), 2, null);
        } catch (Exception e) {
            log.error("[VideoConsumer] 任务执行失败: taskId={}", taskId, e);
            videoGenerationService.updateStatus(task.getId(), 3, e.getMessage());
        }
    }

    private List<String> collectQueueNamesToConsume() {
        List<String> queueNames = new ArrayList<>(taskQueue.listRegisteredQueuesByPrefix(MODEL_QUEUE_PREFIX));
        queueNames.sort(String::compareTo);
        return queueNames;
    }

    private void refreshQueueMaxConcurrent(String queueName, Long modelId) {
        int maxConcurrent = resolveQueueMaxConcurrent(modelId);
        taskQueue.setMaxConcurrent(queueName, maxConcurrent);
    }

    private int resolveQueueMaxConcurrent(Long modelId) {
        AiModel model = resolveQueueModel(modelId);
        // Grok video gateways commonly accept queued jobs but reject concurrent submissions.
        if (generationModelCapabilityService.isGrokImagineVideoModel(model)) {
            return 1;
        }
        Integer configured = model != null ? model.getMaxConcurrency() : null;
        return configured != null && configured > 0 ? configured : 1;
    }

    private AiModel resolveQueueModel(Long modelId) {
        if (modelId != null) {
            try {
                AiModel model = aiModelService.getById(modelId);
                if (model != null && model.getStatus() != null && model.getStatus() == 1) {
                    return model;
                }
            } catch (Exception e) {
                log.warn("[VideoConsumer] 读取视频模型并发配置失败: modelId={}", modelId, e);
            }
        }

        AiModel defaultModel = aiModelService.getDefaultByType(MODEL_TYPE_VIDEO);
        if (defaultModel != null) {
            return defaultModel;
        }

        List<AiModel> videoModels = aiModelService.getListByType(MODEL_TYPE_VIDEO);
        return videoModels.isEmpty() ? null : videoModels.get(0);
    }

    private String resolveQueueName(Long modelId) {
        if (modelId == null) {
            throw new BusinessException("视频生成任务缺少 modelId，无法路由到模型队列");
        }
        return MODEL_QUEUE_PREFIX + modelId;
    }

    @PreDestroy
    public void shutdownWorkerExecutor() {
        workerExecutor.shutdownNow();
        leaseRenewExecutor.shutdownNow();
    }

    /**
     * 将远程视频/封面 URL 下载到持久化存储（本地磁盘 / S3），
     * 并替换 VideoItem 中的 URL 为永久可访问地址。
     */
    private void persistVideoItems(VideoTask task) {
        List<VideoItem> items = videoGenerationService.listItems(task.getId());
        for (VideoItem item : items) {
            boolean updated = false;

            if (StrUtil.isNotBlank(item.getVideoUrl())) {
                try {
                    String persistedUrl = mediaStorageService.downloadAndStore(item.getVideoUrl(), "videos");
                    item.setVideoUrl(persistedUrl);
                    updated = true;
                    log.info("[VideoConsumer] 视频已持久化: itemId={}", item.getId());
                } catch (Exception e) {
                    log.warn("[VideoConsumer] 视频持久化失败（保留原始 URL）: itemId={}, error={}",
                            item.getId(), e.getMessage());
                }
            }

            if (StrUtil.isNotBlank(item.getCoverUrl())) {
                try {
                    String persistedCoverUrl = mediaStorageService.downloadAndStore(item.getCoverUrl(), "images");
                    item.setCoverUrl(persistedCoverUrl);
                    updated = true;
                } catch (Exception e) {
                    log.warn("[VideoConsumer] 视频封面持久化失败: itemId={}, error={}",
                            item.getId(), e.getMessage());
                }
            }

            if (updated) {
                videoGenerationService.updateItem(item);
            }
        }
    }
}
