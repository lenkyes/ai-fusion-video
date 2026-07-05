package com.stonewu.fusion.service.cost;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationCostAnalysisServiceTests {

    private GenerationCostConfigMapper costConfigMapper;
    private AiModelMapper aiModelMapper;
    private ImageTaskMapper imageTaskMapper;
    private ImageItemMapper imageItemMapper;
    private VideoTaskMapper videoTaskMapper;
    private VideoItemMapper videoItemMapper;
    private StoryboardService storyboardService;
    private AssetService assetService;
    private GenerationCostAnalysisService service;

    @BeforeEach
    void setUp() {
        costConfigMapper = mock(GenerationCostConfigMapper.class);
        aiModelMapper = mock(AiModelMapper.class);
        imageTaskMapper = mock(ImageTaskMapper.class);
        imageItemMapper = mock(ImageItemMapper.class);
        videoTaskMapper = mock(VideoTaskMapper.class);
        videoItemMapper = mock(VideoItemMapper.class);
        storyboardService = mock(StoryboardService.class);
        assetService = mock(AssetService.class);
        service = new GenerationCostAnalysisService(
                costConfigMapper,
                aiModelMapper,
                imageTaskMapper,
                imageItemMapper,
                videoTaskMapper,
                videoItemMapper,
                storyboardService,
                assetService
        );
    }

    @Test
    void projectSummaryCountsOnlySuccessfulOutputsAndKeepsManualUploadsFree() {
        when(aiModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                AiModel.builder().id(1L).name("Flux").code("flux").modelType(2).build(),
                AiModel.builder().id(2L).name("Seedance").code("seedance").modelType(3).build()
        ));
        when(costConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                config(1L, "image", "per_image", "0.050000"),
                config(2L, "video", "per_second", "0.100000")
        ));
        when(imageTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                ImageTask.builder().id(10L).projectId(100L).modelId(1L).status(2).successCount(2).category("storyboard_item:501").build(),
                ImageTask.builder().id(11L).projectId(100L).modelId(1L).status(3).successCount(0).category("storyboard_item:502").build()
        ));
        when(imageItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                ImageItem.builder().taskId(10L).status(1).imageUrl("https://cdn.example.com/a.png").build(),
                ImageItem.builder().taskId(10L).status(1).imageUrl("https://cdn.example.com/b.png").build(),
                ImageItem.builder().taskId(11L).status(2).build()
        ));
        when(videoTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                VideoTask.builder().id(20L).projectId(100L).modelId(2L).status(2).successCount(1).duration(5).category("storyboard_item:501").generateMode("image2video").build(),
                VideoTask.builder().id(21L).projectId(100L).modelId(2L).status(3).successCount(0).duration(5).category("storyboard_item:502").generateMode("image2video").build(),
                VideoTask.builder().id(22L).projectId(100L).modelId(2L).status(2).successCount(1).duration(7).category("storyboard_item:501").generateMode("manual_upload").build()
        ));
        when(videoItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                VideoItem.builder().taskId(20L).status(1).videoUrl("https://cdn.example.com/v.mp4").duration(6).build(),
                VideoItem.builder().taskId(21L).status(2).build(),
                VideoItem.builder().taskId(22L).status(1).videoUrl("/media/videos/manual.mp4").duration(7).build()
        ));

        GenerationCostAnalysisService.ProjectCostSummary summary = service.getProjectCostSummary(100L);

        assertThat(summary.imageSuccessCount()).isEqualTo(2);
        assertThat(summary.videoSuccessCount()).isEqualTo(2);
        assertThat(summary.videoSuccessSeconds()).isEqualTo(13);
        assertThat(summary.manualUploadVideoCount()).isEqualTo(1);
        assertThat(summary.unpricedImageCount()).isZero();
        assertThat(summary.unpricedVideoCount()).isZero();
        assertThat(summary.imageCost()).isEqualByComparingTo("0.1000");
        assertThat(summary.videoCost()).isEqualByComparingTo("0.6000");
        assertThat(summary.totalCost()).isEqualByComparingTo("0.7000");
        assertThat(summary.shotCosts()).hasSize(1);
        assertThat(summary.shotCosts().get(0).storyboardItemId()).isEqualTo(501L);
        assertThat(summary.shotCosts().get(0).totalCost()).isEqualByComparingTo("0.7000");
    }

    @Test
    void projectSummaryReportsSuccessfulOutputsWithoutPriceAsUnpriced() {
        when(aiModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                AiModel.builder().id(1L).name("Flux").code("flux").modelType(2).build()
        ));
        when(costConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(imageTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                ImageTask.builder().id(10L).projectId(100L).modelId(1L).status(2).successCount(1).category("asset_item:1").build()
        ));
        when(imageItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                ImageItem.builder().taskId(10L).status(1).imageUrl("https://cdn.example.com/a.png").build()
        ));
        when(videoTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        GenerationCostAnalysisService.ProjectCostSummary summary = service.getProjectCostSummary(100L);

        assertThat(summary.totalCost()).isEqualByComparingTo("0.0000");
        assertThat(summary.imageSuccessCount()).isEqualTo(1);
        assertThat(summary.unpricedImageCount()).isEqualTo(1);
        assertThat(summary.modelCosts()).singleElement().satisfies(modelCost -> {
            assertThat(modelCost.unpricedCount()).isEqualTo(1);
            assertThat(modelCost.cost()).isEqualByComparingTo("0.0000");
        });
    }

    @Test
    void projectSummaryIncludesLegacyStoryboardTasksWithoutProjectId() {
        when(aiModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                AiModel.builder().id(1L).name("Flux").code("flux").modelType(2).build(),
                AiModel.builder().id(2L).name("Seedance").code("seedance").modelType(3).build()
        ));
        when(costConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                config(1L, "image", "per_image", "0.050000"),
                config(2L, "video", "per_second", "0.100000")
        ));
        when(storyboardService.getItemById(501L)).thenReturn(StoryboardItem.builder().id(501L).storyboardId(70L).build());
        when(storyboardService.getById(70L)).thenReturn(Storyboard.builder().id(70L).projectId(100L).build());
        when(storyboardService.getItemById(999L)).thenReturn(StoryboardItem.builder().id(999L).storyboardId(71L).build());
        when(storyboardService.getById(71L)).thenReturn(Storyboard.builder().id(71L).projectId(101L).build());
        when(imageTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                ImageTask.builder().id(10L).modelId(1L).status(2).successCount(1).category("storyboard_item:501").build(),
                ImageTask.builder().id(11L).modelId(1L).status(2).successCount(1).category("storyboard_item:999").build()
        ));
        when(imageItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                ImageItem.builder().taskId(10L).status(1).imageUrl("https://cdn.example.com/a.png").build()
        ));
        when(videoTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                VideoTask.builder().id(20L).modelId(2L).status(2).successCount(1).duration(5).category("storyboard_item:501").build(),
                VideoTask.builder().id(21L).modelId(2L).status(2).successCount(1).duration(5).category("storyboard_item:999").build()
        ));
        when(videoItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                VideoItem.builder().taskId(20L).status(1).videoUrl("https://cdn.example.com/v.mp4").duration(6).build()
        ));

        GenerationCostAnalysisService.ProjectCostSummary summary = service.getProjectCostSummary(100L);

        assertThat(summary.imageTaskCount()).isEqualTo(1);
        assertThat(summary.videoTaskCount()).isEqualTo(1);
        assertThat(summary.imageSuccessCount()).isEqualTo(1);
        assertThat(summary.videoSuccessCount()).isEqualTo(1);
        assertThat(summary.imageCost()).isEqualByComparingTo("0.0500");
        assertThat(summary.videoCost()).isEqualByComparingTo("0.6000");
        assertThat(summary.totalCost()).isEqualByComparingTo("0.6500");
        assertThat(summary.shotCosts()).singleElement().satisfies(shotCost ->
                assertThat(shotCost.storyboardItemId()).isEqualTo(501L));
    }

    @Test
    void projectSummaryIncludesAssetImageTasksWithoutProjectIdWhenAssetItemBelongsToProject() {
        when(aiModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                AiModel.builder().id(1L).name("Flux").code("flux").modelType(2).build()
        ));
        when(costConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                config(1L, "image", "per_image", "0.050000")
        ));
        when(assetService.getItemById(701L)).thenReturn(AssetItem.builder().id(701L).assetId(55L).build());
        when(assetService.getById(55L)).thenReturn(Asset.builder().id(55L).projectId(100L).build());
        when(assetService.getItemById(999L)).thenReturn(AssetItem.builder().id(999L).assetId(56L).build());
        when(assetService.getById(56L)).thenReturn(Asset.builder().id(56L).projectId(101L).build());
        when(imageTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                ImageTask.builder().id(30L).modelId(1L).status(2).successCount(1).category("asset_item:701").build(),
                ImageTask.builder().id(31L).modelId(1L).status(2).successCount(1).category("asset_item:999").build()
        ));
        when(imageItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                ImageItem.builder().taskId(30L).status(1).imageUrl("https://cdn.example.com/asset.png").build()
        ));
        when(videoTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        GenerationCostAnalysisService.ProjectCostSummary summary = service.getProjectCostSummary(100L);

        assertThat(summary.imageTaskCount()).isEqualTo(1);
        assertThat(summary.imageSuccessCount()).isEqualTo(1);
        assertThat(summary.imageCost()).isEqualByComparingTo("0.0500");
        assertThat(summary.unpricedImageCount()).isZero();
    }

    @Test
    void projectSummaryIncludesImageTasksWhoseOutputWasSavedToProjectAsset() {
        when(aiModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                AiModel.builder().id(1L).name("Flux").code("flux").modelType(2).build()
        ));
        when(costConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                config(1L, "image", "per_image", "0.050000")
        ));
        when(assetService.listByProject(100L)).thenReturn(List.of(
                Asset.builder().id(55L).projectId(100L).build()
        ));
        when(assetService.listItems(55L)).thenReturn(List.of(
                AssetItem.builder().id(701L).assetId(55L).sourceType(2).imageUrl("https://cdn.example.com/saved-asset.png").build()
        ));
        when(imageTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                ImageTask.builder().id(30L).modelId(1L).status(2).successCount(3).build()
        ));
        when(imageItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                ImageItem.builder().taskId(30L).status(1).imageUrl("https://cdn.example.com/saved-asset.png").build()
        ));
        when(videoTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        GenerationCostAnalysisService.ProjectCostSummary summary = service.getProjectCostSummary(100L);

        assertThat(summary.imageTaskCount()).isEqualTo(1);
        assertThat(summary.imageSuccessCount()).isEqualTo(1);
        assertThat(summary.imageCost()).isEqualByComparingTo("0.0500");
        assertThat(summary.unpricedImageCount()).isZero();
    }

    @Test
    void projectSummaryReportsAiAssetImagesWithoutMatchedTaskAsUnpriced() {
        when(aiModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                AiModel.builder().id(1L).name("Flux").code("flux").modelType(2).build()
        ));
        when(costConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                config(1L, "image", "per_image", "0.050000")
        ));
        when(assetService.listByProject(100L)).thenReturn(List.of(
                Asset.builder().id(55L).projectId(100L).sourceType(2).coverUrl("https://cdn.example.com/cover.png").build()
        ));
        when(assetService.listItems(55L)).thenReturn(List.of(
                AssetItem.builder().id(701L).assetId(55L).sourceType(2).imageUrl("https://cdn.example.com/asset.png").build()
        ));
        when(imageItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(videoTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        GenerationCostAnalysisService.ProjectCostSummary summary = service.getProjectCostSummary(100L);

        assertThat(summary.imageTaskCount()).isZero();
        assertThat(summary.imageSuccessCount()).isEqualTo(1);
        assertThat(summary.imageCost()).isEqualByComparingTo("0.0000");
        assertThat(summary.unpricedImageCount()).isEqualTo(1);
        assertThat(summary.modelCosts()).singleElement().satisfies(modelCost -> {
            assertThat(modelCost.modelId()).isNull();
            assertThat(modelCost.unpricedCount()).isEqualTo(1);
        });
    }

    @Test
    void projectSummaryTreatsPositivePriceConfigsAsEnabledForExistingRows() {
        GenerationCostConfig disabledPriceConfig = config(2L, "video", "per_second", "0.100000");
        disabledPriceConfig.setEnabled(false);
        when(aiModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                AiModel.builder().id(2L).name("Seedance").code("seedance").modelType(3).build()
        ));
        when(costConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(disabledPriceConfig));
        when(imageTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(videoTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                VideoTask.builder().id(20L).projectId(100L).modelId(2L).status(2).successCount(1).duration(5).category("storyboard_item:501").build()
        ));
        when(videoItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                VideoItem.builder().taskId(20L).status(1).videoUrl("https://cdn.example.com/v.mp4").duration(6).build()
        ));

        GenerationCostAnalysisService.ProjectCostSummary summary = service.getProjectCostSummary(100L);

        assertThat(summary.videoCost()).isEqualByComparingTo("0.6000");
        assertThat(summary.totalCost()).isEqualByComparingTo("0.6000");
        assertThat(summary.unpricedVideoCount()).isZero();
        assertThat(summary.modelCosts()).singleElement().satisfies(modelCost -> {
            assertThat(modelCost.cost()).isEqualByComparingTo("0.6000");
            assertThat(modelCost.unpricedCount()).isZero();
        });
    }

    private GenerationCostConfig config(Long modelId, String mediaType, String billingMode, String unitPrice) {
        return GenerationCostConfig.builder()
                .modelId(modelId)
                .mediaType(mediaType)
                .billingMode(billingMode)
                .unitPrice(new BigDecimal(unitPrice))
                .currency("CNY")
                .enabled(true)
                .build();
    }
}
