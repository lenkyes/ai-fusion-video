package com.stonewu.fusion.service.dashboard;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.common.PageResult;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.entity.project.Project;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.infrastructure.queue.RedisTaskQueue;
import com.stonewu.fusion.mapper.asset.AssetMapper;
import com.stonewu.fusion.mapper.generation.ImageTaskMapper;
import com.stonewu.fusion.mapper.generation.VideoTaskMapper;
import com.stonewu.fusion.mapper.script.ScriptMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardItemMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardMapper;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.generation.consumer.ImageGenerationConsumer;
import com.stonewu.fusion.service.generation.consumer.VideoGenerationConsumer;
import com.stonewu.fusion.service.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_VIDEO = "video";
    private static final String IMAGE_QUEUE_PREFIX = "image_generation:model:";
    private static final String VIDEO_QUEUE_PREFIX = "video_generation:model:";
    private static final int MODEL_TYPE_IMAGE = 2;
    private static final int MODEL_TYPE_VIDEO = 3;

    private final ProjectService projectService;
    private final ScriptMapper scriptMapper;
    private final StoryboardMapper storyboardMapper;
    private final StoryboardItemMapper storyboardItemMapper;
    private final AssetMapper assetMapper;
    private final ImageTaskMapper imageTaskMapper;
    private final VideoTaskMapper videoTaskMapper;
    private final AiModelService aiModelService;
    private final RedisTaskQueue taskQueue;
    private final ImageGenerationConsumer imageGenerationConsumer;
    private final VideoGenerationConsumer videoGenerationConsumer;

    public DashboardAnalyticsResp getAnalytics(Long userId) {
        List<Project> projects = projectService.listAccessibleByUser(userId);
        List<Long> projectIds = projects.stream().map(Project::getId).toList();
        List<Long> storyboardIds = listStoryboardIds(projectIds);

        long imageTaskCount = countImageTasks(userId, null);
        long videoTaskCount = countVideoTasks(userId, null);
        long failedTaskCount = countImageTasks(userId, 3) + countVideoTasks(userId, 3);
        long runningTaskCount = countImageTasks(userId, 1) + countVideoTasks(userId, 1);
        long queuedTaskCount = countImageTasks(userId, 0) + countVideoTasks(userId, 0);
        long completedTaskCount = countImageTasks(userId, 2) + countVideoTasks(userId, 2);
        long totalTaskCount = imageTaskCount + videoTaskCount;

        OverviewResp overview = new OverviewResp(
                projects.size(),
                countScripts(projectIds),
                storyboardIds.size(),
                countStoryboardItems(storyboardIds, false),
                countStoryboardItems(storyboardIds, true),
                countStoryboardVideoItems(storyboardIds),
                countAssets(projectIds),
                imageTaskCount,
                videoTaskCount,
                queuedTaskCount,
                runningTaskCount,
                failedTaskCount,
                completionRate(completedTaskCount, totalTaskCount)
        );

        List<GenerationKindStatsResp> generationStats = List.of(
                buildImageStats(userId),
                buildVideoStats(userId)
        );

        return new DashboardAnalyticsResp(
                overview,
                generationStats,
                getQueueSnapshots(),
                buildDailyActivity(userId),
                pageTasks(userId, null, null, 1, 8).getList()
        );
    }

    public PageResult<GenerationTaskResp> pageTasks(Long userId, String type, Integer status,
                                                    int pageNo, int pageSize) {
        String normalizedType = normalizeType(type);
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int fetchSize = safePageNo * safePageSize;

        if (TYPE_IMAGE.equals(normalizedType)) {
            Page<ImageTask> page = imageTaskMapper.selectPage(new Page<>(safePageNo, safePageSize),
                    imageTaskQuery(userId, status).orderByDesc(ImageTask::getCreateTime));
            return PageResult.of(page).map(this::toTaskResp);
        }
        if (TYPE_VIDEO.equals(normalizedType)) {
            Page<VideoTask> page = videoTaskMapper.selectPage(new Page<>(safePageNo, safePageSize),
                    videoTaskQuery(userId, status).orderByDesc(VideoTask::getCreateTime));
            return PageResult.of(page).map(this::toTaskResp);
        }

        long total = countImageTasks(userId, status) + countVideoTasks(userId, status);
        List<GenerationTaskResp> merged = new ArrayList<>();
        imageTaskMapper.selectPage(new Page<>(1, fetchSize),
                imageTaskQuery(userId, status).orderByDesc(ImageTask::getCreateTime))
                .getRecords()
                .forEach(task -> merged.add(toTaskResp(task)));
        videoTaskMapper.selectPage(new Page<>(1, fetchSize),
                videoTaskQuery(userId, status).orderByDesc(VideoTask::getCreateTime))
                .getRecords()
                .forEach(task -> merged.add(toTaskResp(task)));

        merged.sort(Comparator.comparing(GenerationTaskResp::createTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        int fromIndex = Math.min((safePageNo - 1) * safePageSize, merged.size());
        int toIndex = Math.min(fromIndex + safePageSize, merged.size());
        return new PageResult<>(merged.subList(fromIndex, toIndex), total);
    }

    public List<QueueSnapshotResp> getQueueSnapshots() {
        Map<String, QueueSnapshotResp> snapshots = new LinkedHashMap<>();
        for (AiModel model : aiModelService.getListByType(MODEL_TYPE_IMAGE)) {
            String queueName = IMAGE_QUEUE_PREFIX + model.getId();
            snapshots.put(queueName, buildQueueSnapshot(TYPE_IMAGE, queueName, model));
        }
        for (AiModel model : aiModelService.getListByType(MODEL_TYPE_VIDEO)) {
            String queueName = VIDEO_QUEUE_PREFIX + model.getId();
            snapshots.put(queueName, buildQueueSnapshot(TYPE_VIDEO, queueName, model));
        }
        for (String queueName : taskQueue.listRegisteredQueues()) {
            if (queueName.startsWith(IMAGE_QUEUE_PREFIX)) {
                snapshots.putIfAbsent(queueName, buildQueueSnapshot(TYPE_IMAGE, queueName, null));
            } else if (queueName.startsWith(VIDEO_QUEUE_PREFIX)) {
                snapshots.putIfAbsent(queueName, buildQueueSnapshot(TYPE_VIDEO, queueName, null));
            }
        }
        return new ArrayList<>(snapshots.values());
    }

    public RetryTaskResp retryTask(Long userId, String type, Long id) {
        String normalizedType = normalizeType(type);
        if (TYPE_IMAGE.equals(normalizedType)) {
            return new RetryTaskResp(TYPE_IMAGE, id, imageGenerationConsumer.retryTask(id, userId));
        }
        if (TYPE_VIDEO.equals(normalizedType)) {
            return new RetryTaskResp(TYPE_VIDEO, id, videoGenerationConsumer.retryTask(id, userId));
        }
        throw new BusinessException(400, "不支持的任务类型: " + type);
    }

    public RecoverTasksResp recoverMyExpiredRunningTasks(Long userId) {
        int imageCount = imageGenerationConsumer.recoverExpiredRunningTasks(userId);
        int videoCount = videoGenerationConsumer.recoverExpiredRunningTasks(userId);
        return new RecoverTasksResp(imageCount, videoCount, imageCount + videoCount);
    }

    private GenerationKindStatsResp buildImageStats(Long userId) {
        return new GenerationKindStatsResp(
                TYPE_IMAGE,
                "图片生成",
                countImageTasks(userId, null),
                countImageTasks(userId, 0),
                countImageTasks(userId, 1),
                countImageTasks(userId, 2),
                countImageTasks(userId, 3),
                sumImageSuccessCount(userId),
                averageImageCompletedSeconds(userId)
        );
    }

    private GenerationKindStatsResp buildVideoStats(Long userId) {
        return new GenerationKindStatsResp(
                TYPE_VIDEO,
                "视频生成",
                countVideoTasks(userId, null),
                countVideoTasks(userId, 0),
                countVideoTasks(userId, 1),
                countVideoTasks(userId, 2),
                countVideoTasks(userId, 3),
                sumVideoSuccessCount(userId),
                averageVideoCompletedSeconds(userId)
        );
    }

    private List<DailyActivityResp> buildDailyActivity(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);
        Map<LocalDate, DailyActivityResp> days = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = start.plusDays(i);
            days.put(day, new DailyActivityResp(day.toString(), 0, 0, 0));
        }

        LocalDateTime startTime = start.atStartOfDay();
        for (ImageTask task : imageTaskMapper.selectList(new LambdaQueryWrapper<ImageTask>()
                .eq(ImageTask::getUserId, userId)
                .ge(ImageTask::getCreateTime, startTime)
                .select(ImageTask::getCreateTime, ImageTask::getStatus))) {
            addDailyTask(days, task.getCreateTime(), TYPE_IMAGE, task.getStatus());
        }
        for (VideoTask task : videoTaskMapper.selectList(new LambdaQueryWrapper<VideoTask>()
                .eq(VideoTask::getUserId, userId)
                .ge(VideoTask::getCreateTime, startTime)
                .select(VideoTask::getCreateTime, VideoTask::getStatus))) {
            addDailyTask(days, task.getCreateTime(), TYPE_VIDEO, task.getStatus());
        }
        return new ArrayList<>(days.values());
    }

    private void addDailyTask(Map<LocalDate, DailyActivityResp> days, LocalDateTime createTime,
                              String type, Integer status) {
        if (createTime == null) {
            return;
        }
        LocalDate day = createTime.toLocalDate();
        DailyActivityResp current = days.get(day);
        if (current == null) {
            return;
        }
        long imageTasks = current.imageTasks() + (TYPE_IMAGE.equals(type) ? 1 : 0);
        long videoTasks = current.videoTasks() + (TYPE_VIDEO.equals(type) ? 1 : 0);
        long failedTasks = current.failedTasks() + (status != null && status == 3 ? 1 : 0);
        days.put(day, new DailyActivityResp(current.date(), imageTasks, videoTasks, failedTasks));
    }

    private List<Long> listStoryboardIds(List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return storyboardMapper.selectList(new LambdaQueryWrapper<Storyboard>()
                        .in(Storyboard::getProjectId, projectIds)
                        .select(Storyboard::getId))
                .stream()
                .map(Storyboard::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    private long countScripts(List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return 0;
        }
        return scriptMapper.selectCount(new LambdaQueryWrapper<Script>()
                .in(Script::getProjectId, projectIds));
    }

    private long countAssets(List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return 0;
        }
        return assetMapper.selectCount(new LambdaQueryWrapper<Asset>()
                .in(Asset::getProjectId, projectIds));
    }

    private long countStoryboardItems(List<Long> storyboardIds, boolean generatedImageOnly) {
        if (storyboardIds.isEmpty()) {
            return 0;
        }
        LambdaQueryWrapper<StoryboardItem> wrapper = new LambdaQueryWrapper<StoryboardItem>()
                .in(StoryboardItem::getStoryboardId, storyboardIds);
        if (generatedImageOnly) {
            wrapper.isNotNull(StoryboardItem::getGeneratedImageUrl)
                    .ne(StoryboardItem::getGeneratedImageUrl, "");
        }
        return storyboardItemMapper.selectCount(wrapper);
    }

    private long countStoryboardVideoItems(List<Long> storyboardIds) {
        if (storyboardIds.isEmpty()) {
            return 0;
        }
        return storyboardItemMapper.selectCount(new LambdaQueryWrapper<StoryboardItem>()
                .in(StoryboardItem::getStoryboardId, storyboardIds)
                .and(w -> w.isNotNull(StoryboardItem::getGeneratedVideoUrl)
                        .ne(StoryboardItem::getGeneratedVideoUrl, "")
                        .or()
                        .isNotNull(StoryboardItem::getVideoUrl)
                        .ne(StoryboardItem::getVideoUrl, "")));
    }

    private long countImageTasks(Long userId, Integer status) {
        return imageTaskMapper.selectCount(imageTaskQuery(userId, status));
    }

    private long countVideoTasks(Long userId, Integer status) {
        return videoTaskMapper.selectCount(videoTaskQuery(userId, status));
    }

    private LambdaQueryWrapper<ImageTask> imageTaskQuery(Long userId, Integer status) {
        return new LambdaQueryWrapper<ImageTask>()
                .eq(ImageTask::getUserId, userId)
                .eq(status != null, ImageTask::getStatus, status);
    }

    private LambdaQueryWrapper<VideoTask> videoTaskQuery(Long userId, Integer status) {
        return new LambdaQueryWrapper<VideoTask>()
                .eq(VideoTask::getUserId, userId)
                .eq(status != null, VideoTask::getStatus, status);
    }

    private long sumImageSuccessCount(Long userId) {
        return imageTaskMapper.selectList(new LambdaQueryWrapper<ImageTask>()
                        .eq(ImageTask::getUserId, userId)
                        .select(ImageTask::getSuccessCount))
                .stream()
                .map(ImageTask::getSuccessCount)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
    }

    private long sumVideoSuccessCount(Long userId) {
        return videoTaskMapper.selectList(new LambdaQueryWrapper<VideoTask>()
                        .eq(VideoTask::getUserId, userId)
                        .select(VideoTask::getSuccessCount))
                .stream()
                .map(VideoTask::getSuccessCount)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
    }

    private Long averageImageCompletedSeconds(Long userId) {
        return averageSecondsFromImageTasks(imageTaskMapper.selectList(new LambdaQueryWrapper<ImageTask>()
                .eq(ImageTask::getUserId, userId)
                .eq(ImageTask::getStatus, 2)
                .select(ImageTask::getCreateTime, ImageTask::getUpdateTime)));
    }

    private Long averageVideoCompletedSeconds(Long userId) {
        return averageSecondsFromVideoTasks(videoTaskMapper.selectList(new LambdaQueryWrapper<VideoTask>()
                .eq(VideoTask::getUserId, userId)
                .eq(VideoTask::getStatus, 2)
                .select(VideoTask::getCreateTime, VideoTask::getUpdateTime)));
    }

    private Long averageSeconds(List<? extends TaskTimeView> tasks) {
        long count = 0;
        long total = 0;
        for (TaskTimeView task : tasks) {
            if (task.createTime() == null || task.updateTime() == null) {
                continue;
            }
            long seconds = Math.max(0, Duration.between(task.createTime(), task.updateTime()).toSeconds());
            total += seconds;
            count++;
        }
        return count == 0 ? null : total / count;
    }

    private Long averageSecondsFromImageTasks(List<ImageTask> tasks) {
        return averageSeconds(tasks.stream()
                .map(task -> new TaskTimeView(task.getCreateTime(), task.getUpdateTime()))
                .toList());
    }

    private Long averageSecondsFromVideoTasks(List<VideoTask> tasks) {
        return averageSeconds(tasks.stream()
                .map(task -> new TaskTimeView(task.getCreateTime(), task.getUpdateTime()))
                .toList());
    }

    private GenerationTaskResp toTaskResp(ImageTask task) {
        return new GenerationTaskResp(
                TYPE_IMAGE,
                task.getId(),
                task.getTaskId(),
                task.getProjectId(),
                task.getModelId(),
                modelName(task.getModelId()),
                task.getPrompt(),
                task.getStatus(),
                task.getErrorMsg(),
                task.getCategory(),
                task.getCount(),
                task.getSuccessCount(),
                task.getCreateTime(),
                task.getUpdateTime(),
                task.getStatus() != null && task.getStatus() == 3,
                durationSeconds(task.getCreateTime(), task.getUpdateTime(), task.getStatus()),
                task.getModelId() == null ? null : IMAGE_QUEUE_PREFIX + task.getModelId()
        );
    }

    private GenerationTaskResp toTaskResp(VideoTask task) {
        return new GenerationTaskResp(
                TYPE_VIDEO,
                task.getId(),
                task.getTaskId(),
                task.getProjectId(),
                task.getModelId(),
                modelName(task.getModelId()),
                task.getPrompt(),
                task.getStatus(),
                task.getErrorMsg(),
                task.getCategory(),
                task.getCount(),
                task.getSuccessCount(),
                task.getCreateTime(),
                task.getUpdateTime(),
                task.getStatus() != null && task.getStatus() == 3,
                durationSeconds(task.getCreateTime(), task.getUpdateTime(), task.getStatus()),
                task.getModelId() == null ? null : VIDEO_QUEUE_PREFIX + task.getModelId()
        );
    }

    private QueueSnapshotResp buildQueueSnapshot(String type, String queueName, AiModel model) {
        Long modelId = model != null ? model.getId() : parseModelId(queueName);
        String modelName = model != null ? modelLabel(model) : modelName(modelId);
        return new QueueSnapshotResp(
                type,
                queueName,
                modelId,
                modelName,
                taskQueue.getQueueLength(queueName),
                taskQueue.getConcurrentCount(queueName),
                taskQueue.getMaxConcurrent(queueName)
        );
    }

    private Long parseModelId(String queueName) {
        String prefix = queueName.startsWith(IMAGE_QUEUE_PREFIX) ? IMAGE_QUEUE_PREFIX
                : queueName.startsWith(VIDEO_QUEUE_PREFIX) ? VIDEO_QUEUE_PREFIX : "";
        if (prefix.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(queueName.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String modelName(Long modelId) {
        if (modelId == null) {
            return null;
        }
        AiModel model = aiModelService.getById(modelId);
        return model == null ? "模型 " + modelId : modelLabel(model);
    }

    private String modelLabel(AiModel model) {
        return StrUtil.blankToDefault(model.getName(), model.getCode());
    }

    private String normalizeType(String type) {
        if (StrUtil.isBlank(type) || "all".equalsIgnoreCase(type)) {
            return null;
        }
        String normalized = type.trim().toLowerCase();
        if (TYPE_IMAGE.equals(normalized) || TYPE_VIDEO.equals(normalized)) {
            return normalized;
        }
        return normalized;
    }

    private long completionRate(long completed, long total) {
        if (total <= 0) {
            return 0;
        }
        return Math.round((completed * 100.0) / total);
    }

    private Long durationSeconds(LocalDateTime createTime, LocalDateTime updateTime, Integer status) {
        if (createTime == null || updateTime == null || status == null || (status != 2 && status != 3)) {
            return null;
        }
        return Math.max(0, Duration.between(createTime, updateTime).toSeconds());
    }

    public record DashboardAnalyticsResp(
            OverviewResp overview,
            List<GenerationKindStatsResp> generationStats,
            List<QueueSnapshotResp> queues,
            List<DailyActivityResp> dailyActivity,
            List<GenerationTaskResp> recentTasks
    ) {
    }

    public record OverviewResp(
            long projectCount,
            long scriptCount,
            long storyboardCount,
            long storyboardItemCount,
            long generatedImageShotCount,
            long generatedVideoShotCount,
            long assetCount,
            long imageTaskCount,
            long videoTaskCount,
            long queuedTaskCount,
            long runningTaskCount,
            long failedTaskCount,
            long completionRate
    ) {
    }

    public record GenerationKindStatsResp(
            String type,
            String label,
            long total,
            long queued,
            long running,
            long completed,
            long failed,
            long outputCount,
            Long averageCompletedSeconds
    ) {
    }

    public record QueueSnapshotResp(
            String type,
            String queueName,
            Long modelId,
            String modelName,
            int pendingCount,
            int runningCount,
            int maxConcurrent
    ) {
    }

    public record DailyActivityResp(
            String date,
            long imageTasks,
            long videoTasks,
            long failedTasks
    ) {
    }

    public record GenerationTaskResp(
            String type,
            Long id,
            String taskId,
            Long projectId,
            Long modelId,
            String modelName,
            String prompt,
            Integer status,
            String errorMsg,
            String category,
            Integer count,
            Integer successCount,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            boolean canRetry,
            Long durationSeconds,
            String queueName
    ) {
    }

    public record RetryTaskResp(String type, Long id, String taskId) {
    }

    public record RecoverTasksResp(int imageRecovered, int videoRecovered, int totalRecovered) {
    }

    private record TaskTimeView(LocalDateTime createTime, LocalDateTime updateTime) {
    }
}
