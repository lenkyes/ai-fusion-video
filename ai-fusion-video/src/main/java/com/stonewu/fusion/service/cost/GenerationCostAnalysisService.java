package com.stonewu.fusion.service.cost;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.controller.cost.vo.BatchUpdateCostConfigReqVO;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.cost.GenerationCostConfig;
import com.stonewu.fusion.entity.generation.ImageItem;
import com.stonewu.fusion.entity.generation.ImageTask;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.mapper.ai.AiModelMapper;
import com.stonewu.fusion.mapper.cost.GenerationCostConfigMapper;
import com.stonewu.fusion.mapper.generation.ImageItemMapper;
import com.stonewu.fusion.mapper.generation.ImageTaskMapper;
import com.stonewu.fusion.mapper.generation.VideoItemMapper;
import com.stonewu.fusion.mapper.generation.VideoTaskMapper;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GenerationCostAnalysisService {

    public static final String MEDIA_IMAGE = "image";
    public static final String MEDIA_VIDEO = "video";
    public static final String BILLING_PER_IMAGE = "per_image";
    public static final String BILLING_PER_VIDEO = "per_video";
    public static final String BILLING_PER_SECOND = "per_second";
    public static final String BILLING_FREE = "free";
    private static final String STORYBOARD_ITEM_CATEGORY_PREFIX = "storyboard_item:";
    private static final String ASSET_ITEM_CATEGORY_PREFIX = "asset_item:";

    private final GenerationCostConfigMapper costConfigMapper;
    private final AiModelMapper aiModelMapper;
    private final ImageTaskMapper imageTaskMapper;
    private final ImageItemMapper imageItemMapper;
    private final VideoTaskMapper videoTaskMapper;
    private final VideoItemMapper videoItemMapper;
    private final StoryboardService storyboardService;
    private final AssetService assetService;

    public List<CostConfigRow> listModelCostConfigs(String mediaType) {
        String normalizedMediaType = normalizeMediaType(mediaType);
        List<AiModel> models = aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                .in(AiModel::getModelType, 2, 3)
                .orderByAsc(AiModel::getModelType)
                .orderByAsc(AiModel::getSort)
                .orderByDesc(AiModel::getId));
        List<GenerationCostConfig> configs = costConfigMapper.selectList(new LambdaQueryWrapper<>());
        Map<String, GenerationCostConfig> configMap = configs.stream()
                .collect(Collectors.toMap(
                        config -> configKey(config.getModelId(), config.getMediaType()),
                        Function.identity(),
                        (left, right) -> right));

        List<CostConfigRow> rows = new ArrayList<>();
        for (AiModel model : models) {
            String modelMediaType = modelMediaType(model);
            if (normalizedMediaType != null && !normalizedMediaType.equals(modelMediaType)) {
                continue;
            }
            GenerationCostConfig config = configMap.get(configKey(model.getId(), modelMediaType));
            rows.add(toCostConfigRow(model, modelMediaType, config));
        }
        return rows;
    }

    @Transactional
    public int batchUpdateConfigs(BatchUpdateCostConfigReqVO reqVO) {
        if (reqVO == null || reqVO.getItems() == null || reqVO.getItems().isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (BatchUpdateCostConfigReqVO.Item item : reqVO.getItems()) {
            if (item.getModelId() == null || item.getModelId() <= 0) {
                continue;
            }
            AiModel model = aiModelMapper.selectById(item.getModelId());
            if (model == null) {
                throw new BusinessException("模型不存在: " + item.getModelId());
            }
            String mediaType = normalizeMediaType(item.getMediaType());
            if (mediaType == null) {
                mediaType = modelMediaType(model);
            }
            validateMediaType(mediaType);
            String billingMode = normalizeBillingMode(item.getBillingMode(), mediaType);
            BigDecimal unitPrice = normalizePrice(item.getUnitPrice());
            String currency = StrUtil.blankToDefault(item.getCurrency(), "CNY").trim().toUpperCase(Locale.ROOT);
            Boolean enabled = normalizeEnabled(item.getEnabled(), billingMode, unitPrice);

            GenerationCostConfig existing = costConfigMapper.selectOne(new LambdaQueryWrapper<GenerationCostConfig>()
                    .eq(GenerationCostConfig::getModelId, item.getModelId())
                    .eq(GenerationCostConfig::getMediaType, mediaType)
                    .last("LIMIT 1"));
            if (existing == null) {
                costConfigMapper.insert(GenerationCostConfig.builder()
                        .modelId(item.getModelId())
                        .mediaType(mediaType)
                        .billingMode(billingMode)
                        .unitPrice(unitPrice)
                        .currency(currency)
                        .enabled(enabled)
                        .remark(item.getRemark())
                        .build());
            } else {
                existing.setBillingMode(billingMode);
                existing.setUnitPrice(unitPrice);
                existing.setCurrency(currency);
                existing.setEnabled(enabled);
                existing.setRemark(item.getRemark());
                costConfigMapper.updateById(existing);
            }
            updated++;
        }
        return updated;
    }

    public ProjectCostSummary getProjectCostSummary(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BusinessException("项目ID不能为空");
        }

        Map<Long, AiModel> modelMap = loadModelMap();
        Map<String, GenerationCostConfig> configMap = loadConfigMap();
        Map<String, ModelCostAccumulator> modelAccumulators = new LinkedHashMap<>();
        Map<Long, ShotCostAccumulator> shotAccumulators = new LinkedHashMap<>();
        Map<Long, Long> storyboardItemProjectCache = new HashMap<>();
        Map<Long, Long> assetItemProjectCache = new HashMap<>();

        CostAccumulator total = new CostAccumulator();
        List<ImageCostEntry> imageCostEntries = loadProjectAssetImageCostEntries(projectId);
        if (imageCostEntries.isEmpty()) {
            imageCostEntries = loadProjectImageTaskCostEntries(projectId, storyboardItemProjectCache, assetItemProjectCache);
        }
        int imageTaskCount = (int) imageCostEntries.stream()
                .map(ImageCostEntry::task)
                .filter(Objects::nonNull)
                .map(ImageTask::getId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        for (ImageCostEntry entry : imageCostEntries) {
            ImageTask task = entry.task();
            int successCount = entry.successCount();
            Long modelId = task != null ? task.getModelId() : null;
            String category = task != null ? task.getCategory() : null;
            GenerationCostConfig config = configMap.get(configKey(modelId, MEDIA_IMAGE));
            BigDecimal cost = calculateImageCost(successCount, config);
            boolean priced = successCount > 0 && isBillable(config);
            total.addImage(successCount, cost, priced);
            addModelCost(modelAccumulators, modelId, MEDIA_IMAGE, modelMap, config, successCount, 0, cost, priced);
            addShotImageCost(shotAccumulators, category, successCount, cost, priced);
        }

        List<VideoTask> videoTasks = loadProjectVideoTasks(projectId, storyboardItemProjectCache);
        Map<Long, List<VideoItem>> videoItemsByTask = loadVideoItemsByTask(videoTasks);
        for (VideoTask task : videoTasks) {
            SuccessfulVideoStats stats = successfulVideoStats(task, videoItemsByTask.get(task.getId()));
            boolean manualUpload = "manual_upload".equalsIgnoreCase(StrUtil.blankToDefault(task.getGenerateMode(), ""));
            GenerationCostConfig config = manualUpload ? null : configMap.get(configKey(task.getModelId(), MEDIA_VIDEO));
            BigDecimal cost = manualUpload ? BigDecimal.ZERO : calculateVideoCost(stats.count(), stats.seconds(), config);
            boolean priced = !manualUpload && stats.count() > 0 && isBillable(config);
            total.addVideo(stats.count(), stats.seconds(), cost, priced, manualUpload);
            boolean pricedOrFreeByRule = priced || manualUpload;
            addModelCost(modelAccumulators, task.getModelId(), MEDIA_VIDEO, modelMap, config,
                    stats.count(), stats.seconds(), cost, pricedOrFreeByRule);
            addShotVideoCost(shotAccumulators, task.getCategory(), stats.count(), stats.seconds(), cost, pricedOrFreeByRule);
        }

        List<ModelCostBreakdown> modelCosts = modelAccumulators.values().stream()
                .map(ModelCostAccumulator::toBreakdown)
                .sorted(Comparator.comparing(ModelCostBreakdown::cost).reversed())
                .toList();
        List<ShotCostBreakdown> shotCosts = shotAccumulators.values().stream()
                .filter(ShotCostAccumulator::hasOutput)
                .map(ShotCostAccumulator::toBreakdown)
                .sorted(Comparator.comparing(ShotCostBreakdown::totalCost).reversed())
                .limit(100)
                .toList();

        return new ProjectCostSummary(
                projectId,
                money(total.totalCost()),
                money(total.imageCost),
                money(total.videoCost),
                imageTaskCount,
                videoTasks.size(),
                total.imageSuccessCount,
                total.videoSuccessCount,
                total.videoSuccessSeconds,
                total.unpricedImageCount,
                total.unpricedVideoCount,
                total.manualUploadVideoCount,
                money(total.costPerFinalSecond()),
                modelCosts,
                shotCosts
        );
    }

    private Map<Long, AiModel> loadModelMap() {
        return aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>())
                .stream()
                .collect(Collectors.toMap(AiModel::getId, Function.identity(), (left, right) -> right));
    }

    private Map<String, GenerationCostConfig> loadConfigMap() {
        return costConfigMapper.selectList(new LambdaQueryWrapper<>())
                .stream()
                .collect(Collectors.toMap(
                        config -> configKey(config.getModelId(), config.getMediaType()),
                        Function.identity(),
                        (left, right) -> right));
    }

    private List<ImageCostEntry> loadProjectImageTaskCostEntries(Long projectId,
                                                                 Map<Long, Long> storyboardItemProjectCache,
                                                                 Map<Long, Long> assetItemProjectCache) {
        List<ImageTask> imageTasks = loadProjectImageTasks(projectId, storyboardItemProjectCache, assetItemProjectCache);
        Map<Long, List<ImageItem>> imageItemsByTask = loadImageItemsByTask(imageTasks);
        List<ImageCostEntry> entries = new ArrayList<>();
        for (ImageTask task : imageTasks) {
            int successCount = countSuccessfulImages(task, imageItemsByTask.get(task.getId()));
            if (successCount > 0) {
                entries.add(new ImageCostEntry(task, successCount));
            }
        }
        return entries;
    }

    private List<ImageCostEntry> loadProjectAssetImageCostEntries(Long projectId) {
        List<AssetImageRef> assetImages = loadProjectAssetImageRefs(projectId);
        if (assetImages.isEmpty()) {
            return List.of();
        }

        List<String> imageUrls = assetImages.stream()
                .map(AssetImageRef::imageUrl)
                .distinct()
                .toList();
        Map<String, ImageTask> taskByUrl = loadImageTasksByOutputUrl(imageUrls);
        Map<String, Integer> successCounts = new LinkedHashMap<>();
        Map<String, ImageTask> tasks = new HashMap<>();
        for (AssetImageRef image : assetImages) {
            ImageTask task = taskByUrl.get(image.imageUrl());
            if (task == null && !image.aiGenerated()) {
                continue;
            }
            String key = task != null && task.getId() != null ? "task:" + task.getId() : "unmatched";
            successCounts.merge(key, 1, Integer::sum);
            tasks.putIfAbsent(key, task);
        }
        return successCounts.entrySet().stream()
                .map(entry -> new ImageCostEntry(tasks.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    private Map<String, ImageTask> loadImageTasksByOutputUrl(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return Map.of();
        }

        List<ImageItem> imageItems = imageItemMapper.selectList(new LambdaQueryWrapper<ImageItem>()
                .eq(ImageItem::getStatus, 1)
                .and(wrapper -> wrapper.in(ImageItem::getImageUrl, imageUrls)
                        .or()
                        .in(ImageItem::getThumbnailUrl, imageUrls)));
        List<Long> taskIds = imageItems.stream()
                .map(ImageItem::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, ImageTask> taskMap = imageTaskMapper.selectList(new LambdaQueryWrapper<ImageTask>()
                        .in(ImageTask::getId, taskIds))
                .stream()
                .collect(Collectors.toMap(ImageTask::getId, Function.identity(), (left, right) -> left));
        Map<String, ImageTask> taskByUrl = new HashMap<>();
        for (ImageItem item : imageItems) {
            ImageTask task = taskMap.get(item.getTaskId());
            if (task == null) {
                continue;
            }
            putTaskByUrl(taskByUrl, item.getImageUrl(), task);
            putTaskByUrl(taskByUrl, item.getThumbnailUrl(), task);
        }
        return taskByUrl;
    }

    private List<AssetImageRef> loadProjectAssetImageRefs(Long projectId) {
        List<Asset> assets = assetService.listByProject(projectId);
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }

        Map<String, AssetImageRef> refs = new LinkedHashMap<>();
        for (Asset asset : assets) {
            if (asset == null || asset.getId() == null) {
                continue;
            }
            List<AssetItem> items = assetService.listItems(asset.getId());
            boolean hasItemImage = false;
            if (items != null && !items.isEmpty()) {
                for (AssetItem item : items) {
                    if (item == null) {
                        continue;
                    }
                    String imageUrl = firstNotBlank(item.getImageUrl(), item.getThumbnailUrl());
                    if (imageUrl != null) {
                        hasItemImage = true;
                        putAssetImageRef(refs, imageUrl, isAiGenerated(item.getSourceType()));
                    }
                }
            }
            if (!hasItemImage) {
                putAssetImageRef(refs, asset.getCoverUrl(), isAiGenerated(asset.getSourceType()));
            }
        }
        return new ArrayList<>(refs.values());
    }

    private List<ImageTask> loadProjectImageTasks(Long projectId,
                                                  Map<Long, Long> storyboardItemProjectCache,
                                                  Map<Long, Long> assetItemProjectCache) {
        List<ImageTask> candidates = imageTaskMapper.selectList(new LambdaQueryWrapper<ImageTask>()
                .and(wrapper -> wrapper.eq(ImageTask::getProjectId, projectId)
                        .or()
                        .likeRight(ImageTask::getCategory, STORYBOARD_ITEM_CATEGORY_PREFIX)
                        .or()
                        .likeRight(ImageTask::getCategory, ASSET_ITEM_CATEGORY_PREFIX))
                .orderByDesc(ImageTask::getCreateTime));
        Map<Long, ImageTask> taskMap = new LinkedHashMap<>();
        for (ImageTask task : candidates) {
            if (belongsToProject(task.getProjectId(), task.getCategory(), projectId,
                    storyboardItemProjectCache, assetItemProjectCache)) {
                taskMap.put(task.getId(), task);
            }
        }
        for (ImageTask task : loadProjectAssetImageTasksByOutputUrl(projectId)) {
            taskMap.putIfAbsent(task.getId(), task);
        }
        return new ArrayList<>(taskMap.values());
    }

    private List<VideoTask> loadProjectVideoTasks(Long projectId, Map<Long, Long> storyboardItemProjectCache) {
        List<VideoTask> candidates = videoTaskMapper.selectList(new LambdaQueryWrapper<VideoTask>()
                .and(wrapper -> wrapper.eq(VideoTask::getProjectId, projectId)
                        .or()
                        .likeRight(VideoTask::getCategory, STORYBOARD_ITEM_CATEGORY_PREFIX))
                .orderByDesc(VideoTask::getCreateTime));
        return candidates.stream()
                .filter(task -> belongsToProject(task.getProjectId(), task.getCategory(), projectId,
                        storyboardItemProjectCache, new HashMap<>()))
                .toList();
    }

    private boolean belongsToProject(Long taskProjectId,
                                     String category,
                                     Long projectId,
                                     Map<Long, Long> storyboardItemProjectCache,
                                     Map<Long, Long> assetItemProjectCache) {
        if (Objects.equals(taskProjectId, projectId)) {
            return true;
        }
        if (taskProjectId != null && taskProjectId > 0) {
            return false;
        }
        Long storyboardItemId = parseStoryboardItemId(category);
        if (storyboardItemId == null) {
            Long assetItemId = parseAssetItemId(category);
            return assetItemId != null
                    && Objects.equals(resolveAssetItemProjectId(assetItemId, assetItemProjectCache), projectId);
        }
        return Objects.equals(resolveStoryboardItemProjectId(storyboardItemId, storyboardItemProjectCache), projectId);
    }

    private Long resolveStoryboardItemProjectId(Long storyboardItemId, Map<Long, Long> storyboardItemProjectCache) {
        if (storyboardItemProjectCache.containsKey(storyboardItemId)) {
            return storyboardItemProjectCache.get(storyboardItemId);
        }

        Long projectId = null;
        try {
            StoryboardItem item = storyboardService.getItemById(storyboardItemId);
            if (item != null && item.getStoryboardId() != null) {
                Storyboard storyboard = storyboardService.getById(item.getStoryboardId());
                projectId = storyboard != null ? storyboard.getProjectId() : null;
            }
        } catch (Exception ignored) {
            // Historical tasks without a valid storyboard item cannot be assigned to a project.
        }
        storyboardItemProjectCache.put(storyboardItemId, projectId);
        return projectId;
    }

    private Long resolveAssetItemProjectId(Long assetItemId, Map<Long, Long> assetItemProjectCache) {
        if (assetItemProjectCache.containsKey(assetItemId)) {
            return assetItemProjectCache.get(assetItemId);
        }

        Long projectId = null;
        try {
            AssetItem item = assetService.getItemById(assetItemId);
            if (item != null && item.getAssetId() != null) {
                Asset asset = assetService.getById(item.getAssetId());
                projectId = asset != null ? asset.getProjectId() : null;
            }
        } catch (Exception ignored) {
            // Historical tasks without a valid asset item cannot be assigned to a project.
        }
        assetItemProjectCache.put(assetItemId, projectId);
        return projectId;
    }

    private List<ImageTask> loadProjectAssetImageTasksByOutputUrl(Long projectId) {
        List<String> imageUrls = loadProjectAssetImageUrls(projectId);
        if (imageUrls.isEmpty()) {
            return List.of();
        }
        List<ImageItem> imageItems = imageItemMapper.selectList(new LambdaQueryWrapper<ImageItem>()
                .in(ImageItem::getImageUrl, imageUrls)
                .eq(ImageItem::getStatus, 1));
        List<Long> taskIds = imageItems.stream()
                .map(ImageItem::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return imageTaskMapper.selectList(new LambdaQueryWrapper<ImageTask>()
                .in(ImageTask::getId, taskIds)
                .orderByDesc(ImageTask::getCreateTime));
    }

    private List<String> loadProjectAssetImageUrls(Long projectId) {
        List<Asset> assets = assetService.listByProject(projectId);
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        List<String> imageUrls = new ArrayList<>();
        for (Asset asset : assets) {
            if (asset == null || asset.getId() == null) {
                continue;
            }
            addIfNotBlank(imageUrls, asset.getCoverUrl());
            List<AssetItem> items = assetService.listItems(asset.getId());
            if (items == null || items.isEmpty()) {
                continue;
            }
            for (AssetItem item : items) {
                if (item == null) {
                    continue;
                }
                addIfNotBlank(imageUrls, item.getImageUrl());
                addIfNotBlank(imageUrls, item.getThumbnailUrl());
            }
        }
        return imageUrls.stream().distinct().toList();
    }

    private Map<Long, List<ImageItem>> loadImageItemsByTask(List<ImageTask> tasks) {
        List<Long> taskIds = tasks.stream().map(ImageTask::getId).filter(Objects::nonNull).toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return imageItemMapper.selectList(new LambdaQueryWrapper<ImageItem>().in(ImageItem::getTaskId, taskIds))
                .stream()
                .collect(Collectors.groupingBy(ImageItem::getTaskId));
    }

    private Map<Long, List<VideoItem>> loadVideoItemsByTask(List<VideoTask> tasks) {
        List<Long> taskIds = tasks.stream().map(VideoTask::getId).filter(Objects::nonNull).toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return videoItemMapper.selectList(new LambdaQueryWrapper<VideoItem>().in(VideoItem::getTaskId, taskIds))
                .stream()
                .collect(Collectors.groupingBy(VideoItem::getTaskId));
    }

    private int countSuccessfulImages(ImageTask task, List<ImageItem> items) {
        int itemCount = items == null ? 0 : (int) items.stream()
                .filter(item -> Objects.equals(item.getStatus(), 1) && StrUtil.isNotBlank(item.getImageUrl()))
                .count();
        if (itemCount > 0) {
            return itemCount;
        }
        if (Objects.equals(task.getStatus(), 2) && task.getSuccessCount() != null && task.getSuccessCount() > 0) {
            return task.getSuccessCount();
        }
        return 0;
    }

    private SuccessfulVideoStats successfulVideoStats(VideoTask task, List<VideoItem> items) {
        int count = 0;
        int seconds = 0;
        if (items != null) {
            for (VideoItem item : items) {
                if (!Objects.equals(item.getStatus(), 1) || StrUtil.isBlank(item.getVideoUrl())) {
                    continue;
                }
                count++;
                seconds += positiveOrDefault(item.getDuration(), task.getDuration());
            }
        }
        if (count == 0 && Objects.equals(task.getStatus(), 2) && task.getSuccessCount() != null && task.getSuccessCount() > 0) {
            count = task.getSuccessCount();
            seconds = positive(task.getDuration()) * count;
        }
        return new SuccessfulVideoStats(count, seconds);
    }

    private BigDecimal calculateImageCost(int successCount, GenerationCostConfig config) {
        if (successCount <= 0 || !isBillable(config)) {
            return BigDecimal.ZERO;
        }
        if (BILLING_PER_IMAGE.equals(config.getBillingMode())) {
            return config.getUnitPrice().multiply(BigDecimal.valueOf(successCount));
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal calculateVideoCost(int successCount, int seconds, GenerationCostConfig config) {
        if (successCount <= 0 || !isBillable(config)) {
            return BigDecimal.ZERO;
        }
        if (BILLING_PER_SECOND.equals(config.getBillingMode())) {
            return config.getUnitPrice().multiply(BigDecimal.valueOf(Math.max(seconds, 0)));
        }
        if (BILLING_PER_VIDEO.equals(config.getBillingMode())) {
            return config.getUnitPrice().multiply(BigDecimal.valueOf(successCount));
        }
        return BigDecimal.ZERO;
    }

    private boolean isBillable(GenerationCostConfig config) {
        return config != null
                && isCostConfigEnabled(config)
                && !BILLING_FREE.equals(config.getBillingMode())
                && config.getUnitPrice() != null
                && config.getUnitPrice().compareTo(BigDecimal.ZERO) > 0;
    }

    private void addModelCost(Map<String, ModelCostAccumulator> accumulators,
                              Long modelId,
                              String mediaType,
                              Map<Long, AiModel> modelMap,
                              GenerationCostConfig config,
                              int successCount,
                              int seconds,
                              BigDecimal cost,
                              boolean priced) {
        String key = configKey(modelId, mediaType);
        ModelCostAccumulator accumulator = accumulators.computeIfAbsent(key,
                ignored -> new ModelCostAccumulator(modelId, mediaType, modelMap.get(modelId), config));
        accumulator.taskCount++;
        accumulator.successCount += successCount;
        accumulator.successSeconds += seconds;
        accumulator.cost = accumulator.cost.add(cost);
        if (successCount > 0 && !priced) {
            accumulator.unpricedCount += successCount;
        }
    }

    private void addShotImageCost(Map<Long, ShotCostAccumulator> accumulators,
                                  String category,
                                  int successCount,
                                  BigDecimal cost,
                                  boolean priced) {
        Long itemId = parseStoryboardItemId(category);
        if (itemId == null) {
            return;
        }
        ShotCostAccumulator accumulator = accumulators.computeIfAbsent(itemId, ShotCostAccumulator::new);
        accumulator.imageSuccessCount += successCount;
        accumulator.imageCost = accumulator.imageCost.add(cost);
        if (successCount > 0 && !priced) {
            accumulator.unpricedImageCount += successCount;
        }
    }

    private void addShotVideoCost(Map<Long, ShotCostAccumulator> accumulators,
                                  String category,
                                  int successCount,
                                  int seconds,
                                  BigDecimal cost,
                                  boolean priced) {
        Long itemId = parseStoryboardItemId(category);
        if (itemId == null) {
            return;
        }
        ShotCostAccumulator accumulator = accumulators.computeIfAbsent(itemId, ShotCostAccumulator::new);
        accumulator.videoSuccessCount += successCount;
        accumulator.videoSuccessSeconds += seconds;
        accumulator.videoCost = accumulator.videoCost.add(cost);
        if (successCount > 0 && !priced) {
            accumulator.unpricedVideoCount += successCount;
        }
    }

    private CostConfigRow toCostConfigRow(AiModel model, String mediaType, GenerationCostConfig config) {
        return new CostConfigRow(
                config != null ? config.getId() : null,
                model.getId(),
                model.getName(),
                model.getCode(),
                model.getModelType(),
                mediaType,
                config != null ? config.getBillingMode() : defaultBillingMode(mediaType),
                config != null ? normalizePrice(config.getUnitPrice()) : BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP),
                config != null ? StrUtil.blankToDefault(config.getCurrency(), "CNY") : "CNY",
                config != null ? isCostConfigEnabled(config) : true,
                config != null,
                config != null ? config.getRemark() : null,
                config != null ? config.getUpdateTime() : null
        );
    }

    private String modelMediaType(AiModel model) {
        return Objects.equals(model.getModelType(), 3) ? MEDIA_VIDEO : MEDIA_IMAGE;
    }

    private String defaultBillingMode(String mediaType) {
        return MEDIA_VIDEO.equals(mediaType) ? BILLING_PER_SECOND : BILLING_PER_IMAGE;
    }

    private String normalizeMediaType(String mediaType) {
        if (StrUtil.isBlank(mediaType)) {
            return null;
        }
        return mediaType.trim().toLowerCase(Locale.ROOT);
    }

    private void validateMediaType(String mediaType) {
        if (!MEDIA_IMAGE.equals(mediaType) && !MEDIA_VIDEO.equals(mediaType)) {
            throw new BusinessException("不支持的成本媒体类型: " + mediaType);
        }
    }

    private String normalizeBillingMode(String billingMode, String mediaType) {
        if (StrUtil.isBlank(billingMode)) {
            return defaultBillingMode(mediaType);
        }
        String normalized = billingMode.trim().toLowerCase(Locale.ROOT);
        if (MEDIA_IMAGE.equals(mediaType)
                && (BILLING_PER_IMAGE.equals(normalized) || BILLING_FREE.equals(normalized))) {
            return normalized;
        }
        if (MEDIA_VIDEO.equals(mediaType)
                && (BILLING_PER_SECOND.equals(normalized)
                || BILLING_PER_VIDEO.equals(normalized)
                || BILLING_FREE.equals(normalized))) {
            return normalized;
        }
        throw new BusinessException("不支持的计费方式: " + billingMode);
    }

    private BigDecimal normalizePrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        return price.setScale(6, RoundingMode.HALF_UP);
    }

    private Boolean normalizeEnabled(Boolean enabled, String billingMode, BigDecimal unitPrice) {
        if (BILLING_FREE.equals(billingMode)) {
            return false;
        }
        if (unitPrice != null && unitPrice.compareTo(BigDecimal.ZERO) > 0) {
            return true;
        }
        return enabled != null ? enabled : true;
    }

    private boolean isCostConfigEnabled(GenerationCostConfig config) {
        if (config == null || BILLING_FREE.equals(config.getBillingMode())) {
            return false;
        }
        if (config.getUnitPrice() != null && config.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            return true;
        }
        return Boolean.TRUE.equals(config.getEnabled());
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : value.setScale(4, RoundingMode.HALF_UP);
    }

    private String configKey(Long modelId, String mediaType) {
        return (modelId == null ? 0 : modelId) + ":" + StrUtil.blankToDefault(mediaType, "");
    }

    private Long parseStoryboardItemId(String category) {
        return parseCategoryId(category, STORYBOARD_ITEM_CATEGORY_PREFIX);
    }

    private Long parseAssetItemId(String category) {
        return parseCategoryId(category, ASSET_ITEM_CATEGORY_PREFIX);
    }

    private Long parseCategoryId(String category, String prefix) {
        if (StrUtil.isBlank(category) || StrUtil.isBlank(prefix) || !category.startsWith(prefix)) {
            return null;
        }
        String raw = category.substring(prefix.length());
        int colonIndex = raw.indexOf(':');
        if (colonIndex >= 0) {
            raw = raw.substring(0, colonIndex);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void addIfNotBlank(List<String> values, String value) {
        if (StrUtil.isNotBlank(value)) {
            values.add(value);
        }
    }

    private void putTaskByUrl(Map<String, ImageTask> taskByUrl, String imageUrl, ImageTask task) {
        String normalizedUrl = normalizeUrl(imageUrl);
        if (normalizedUrl != null) {
            taskByUrl.putIfAbsent(normalizedUrl, task);
        }
    }

    private void putAssetImageRef(Map<String, AssetImageRef> refs, String imageUrl, boolean aiGenerated) {
        String normalizedUrl = normalizeUrl(imageUrl);
        if (normalizedUrl == null) {
            return;
        }
        AssetImageRef existing = refs.get(normalizedUrl);
        if (existing == null || (aiGenerated && !existing.aiGenerated())) {
            refs.put(normalizedUrl, new AssetImageRef(normalizedUrl, aiGenerated));
        }
    }

    private boolean isAiGenerated(Integer sourceType) {
        return Objects.equals(sourceType, 2);
    }

    private String firstNotBlank(String first, String second) {
        String normalizedFirst = normalizeUrl(first);
        return normalizedFirst != null ? normalizedFirst : normalizeUrl(second);
    }

    private String normalizeUrl(String imageUrl) {
        if (StrUtil.isBlank(imageUrl)) {
            return null;
        }
        return imageUrl.trim();
    }

    private int positive(Integer value) {
        return value != null && value > 0 ? value : 0;
    }

    private int positiveOrDefault(Integer value, Integer fallback) {
        int resolved = positive(value);
        return resolved > 0 ? resolved : positive(fallback);
    }

    public record CostConfigRow(Long id,
                                Long modelId,
                                String modelName,
                                String modelCode,
                                Integer modelType,
                                String mediaType,
                                String billingMode,
                                BigDecimal unitPrice,
                                String currency,
                                Boolean enabled,
                                Boolean configured,
                                String remark,
                                LocalDateTime updateTime) {
    }

    public record ProjectCostSummary(Long projectId,
                                     BigDecimal totalCost,
                                     BigDecimal imageCost,
                                     BigDecimal videoCost,
                                     int imageTaskCount,
                                     int videoTaskCount,
                                     int imageSuccessCount,
                                     int videoSuccessCount,
                                     int videoSuccessSeconds,
                                     int unpricedImageCount,
                                     int unpricedVideoCount,
                                     int manualUploadVideoCount,
                                     BigDecimal costPerFinalSecond,
                                     List<ModelCostBreakdown> modelCosts,
                                     List<ShotCostBreakdown> shotCosts) {
    }

    public record ModelCostBreakdown(Long modelId,
                                     String modelName,
                                     String modelCode,
                                     String mediaType,
                                     String billingMode,
                                     BigDecimal unitPrice,
                                     String currency,
                                     int taskCount,
                                     int successCount,
                                     int successSeconds,
                                     int unpricedCount,
                                     BigDecimal cost) {
    }

    public record ShotCostBreakdown(Long storyboardItemId,
                                    int imageSuccessCount,
                                    int videoSuccessCount,
                                    int videoSuccessSeconds,
                                    int unpricedImageCount,
                                    int unpricedVideoCount,
                                    BigDecimal imageCost,
                                    BigDecimal videoCost,
                                    BigDecimal totalCost) {
    }

    private record SuccessfulVideoStats(int count, int seconds) {
    }

    private record ImageCostEntry(ImageTask task, int successCount) {
    }

    private record AssetImageRef(String imageUrl, boolean aiGenerated) {
    }

    private static class CostAccumulator {
        private BigDecimal imageCost = BigDecimal.ZERO;
        private BigDecimal videoCost = BigDecimal.ZERO;
        private int imageSuccessCount;
        private int videoSuccessCount;
        private int videoSuccessSeconds;
        private int unpricedImageCount;
        private int unpricedVideoCount;
        private int manualUploadVideoCount;

        void addImage(int successCount, BigDecimal cost, boolean priced) {
            imageSuccessCount += successCount;
            imageCost = imageCost.add(cost);
            if (successCount > 0 && !priced) {
                unpricedImageCount += successCount;
            }
        }

        void addVideo(int successCount, int seconds, BigDecimal cost, boolean priced, boolean manualUpload) {
            videoSuccessCount += successCount;
            videoSuccessSeconds += seconds;
            videoCost = videoCost.add(cost);
            if (manualUpload) {
                manualUploadVideoCount += successCount;
            } else if (successCount > 0 && !priced) {
                unpricedVideoCount += successCount;
            }
        }

        BigDecimal totalCost() {
            return imageCost.add(videoCost);
        }

        BigDecimal costPerFinalSecond() {
            if (videoSuccessSeconds <= 0) {
                return BigDecimal.ZERO;
            }
            return totalCost().divide(BigDecimal.valueOf(videoSuccessSeconds), 6, RoundingMode.HALF_UP);
        }
    }

    private class ModelCostAccumulator {
        private final Long modelId;
        private final String mediaType;
        private final AiModel model;
        private final GenerationCostConfig config;
        private int taskCount;
        private int successCount;
        private int successSeconds;
        private int unpricedCount;
        private BigDecimal cost = BigDecimal.ZERO;

        ModelCostAccumulator(Long modelId, String mediaType, AiModel model, GenerationCostConfig config) {
            this.modelId = modelId;
            this.mediaType = mediaType;
            this.model = model;
            this.config = config;
        }

        ModelCostBreakdown toBreakdown() {
            return new ModelCostBreakdown(
                    modelId,
                    model != null ? model.getName() : "未配置模型",
                    model != null ? model.getCode() : null,
                    mediaType,
                    config != null ? config.getBillingMode() : defaultBillingMode(mediaType),
                    config != null ? normalizePrice(config.getUnitPrice()) : BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP),
                    config != null ? StrUtil.blankToDefault(config.getCurrency(), "CNY") : "CNY",
                    taskCount,
                    successCount,
                    successSeconds,
                    unpricedCount,
                    money(cost)
            );
        }
    }

    private class ShotCostAccumulator {
        private final Long storyboardItemId;
        private int imageSuccessCount;
        private int videoSuccessCount;
        private int videoSuccessSeconds;
        private int unpricedImageCount;
        private int unpricedVideoCount;
        private BigDecimal imageCost = BigDecimal.ZERO;
        private BigDecimal videoCost = BigDecimal.ZERO;

        ShotCostAccumulator(Long storyboardItemId) {
            this.storyboardItemId = storyboardItemId;
        }

        boolean hasOutput() {
            return imageSuccessCount > 0 || videoSuccessCount > 0;
        }

        ShotCostBreakdown toBreakdown() {
            return new ShotCostBreakdown(
                    storyboardItemId,
                    imageSuccessCount,
                    videoSuccessCount,
                    videoSuccessSeconds,
                    unpricedImageCount,
                    unpricedVideoCount,
                    money(imageCost),
                    money(videoCost),
                    money(imageCost.add(videoCost))
            );
        }
    }
}
