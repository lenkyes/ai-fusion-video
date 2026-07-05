package com.stonewu.fusion.service.storyboard;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardVideoQuality;
import com.stonewu.fusion.mapper.storyboard.StoryboardVideoQualityMapper;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryboardVideoQualityServiceTests {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StoryboardVideoQuality.class);
    }

    private StoryboardService storyboardService;
    private VideoGenerationService videoGenerationService;
    private StoryboardVideoQualityMapper qualityMapper;
    private StoryboardVideoQualityService service;
    private Map<Long, StoryboardVideoQuality> store;
    private Deque<StoryboardVideoQuality> selectOneQueue;
    private AtomicInteger updateCalls;
    private AtomicReference<Long> pendingSelectedQualityId;
    private long nextId;

    @BeforeEach
    void setUp() {
        storyboardService = mock(StoryboardService.class);
        videoGenerationService = mock(VideoGenerationService.class);
        qualityMapper = mock(StoryboardVideoQualityMapper.class);
        service = new StoryboardVideoQualityService(storyboardService, videoGenerationService, qualityMapper);
        store = new LinkedHashMap<>();
        selectOneQueue = new ArrayDeque<>();
        updateCalls = new AtomicInteger();
        pendingSelectedQualityId = new AtomicReference<>();
        nextId = 1L;
        stubQualityMapper();
    }

    @Test
    void evaluateScoresAndSortsMultipleCandidates() {
        StoryboardItem item = storyboardItem();
        VideoTask goodTask = goodTask();
        VideoTask weakTask = weakTask();
        when(storyboardService.getItemById(10L)).thenReturn(item);
        when(videoGenerationService.listByCategoryFamily("storyboard_item:10", 7L, null))
                .thenReturn(List.of(goodTask, weakTask));
        when(videoGenerationService.listItems(101L)).thenReturn(List.of(goodVideo()));
        when(videoGenerationService.listItems(102L)).thenReturn(List.of(weakVideo()));

        StoryboardVideoQualityService.QualityReviewResult result =
                service.evaluateStoryboardItem(10L, 7L, false, null);

        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().get(0).getVideoUrl()).isEqualTo("https://cdn.example.com/good.mp4");
        assertThat(result.candidates().get(0).getTotalScore())
                .isGreaterThan(result.candidates().get(1).getTotalScore());
        assertThat(result.selectedCandidate()).isNull();
        verify(storyboardService, never()).updateItem(any());
    }

    @Test
    void evaluateAutoSelectsBestCandidateAndBackfillsStoryboardItem() {
        StoryboardItem item = storyboardItem();
        VideoTask goodTask = goodTask();
        VideoTask weakTask = weakTask();
        when(storyboardService.getItemById(10L)).thenReturn(item);
        when(videoGenerationService.listByCategoryFamily("storyboard_item:10", 7L, null))
                .thenReturn(List.of(weakTask, goodTask));
        when(videoGenerationService.listItems(101L)).thenReturn(List.of(goodVideo()));
        when(videoGenerationService.listItems(102L)).thenReturn(List.of(weakVideo()));

        StoryboardVideoQualityService.QualityReviewResult result =
                service.evaluateStoryboardItem(10L, 7L, true, 70);

        assertThat(result.selectedCandidate()).isNotNull();
        assertThat(result.selectedCandidate().getVideoUrl()).isEqualTo("https://cdn.example.com/good.mp4");
        assertThat(result.candidates()).anySatisfy(q -> {
            assertThat(q.getVideoUrl()).isEqualTo("https://cdn.example.com/good.mp4");
            assertThat(q.getSelected()).isTrue();
        });

        ArgumentCaptor<StoryboardItem> itemCaptor = ArgumentCaptor.forClass(StoryboardItem.class);
        verify(storyboardService).updateItem(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getId()).isEqualTo(10L);
        assertThat(itemCaptor.getValue().getGeneratedVideoUrl()).isEqualTo("https://cdn.example.com/good.mp4");
        assertThat(itemCaptor.getValue().getVideoPrompt()).isEqualTo(goodTask.getPrompt());
    }

    @Test
    void evaluateReturnsEmptyResultWhenNoCandidateExists() {
        StoryboardItem item = storyboardItem();
        item.setGeneratedVideoUrl(null);
        item.setVideoUrl(null);
        when(storyboardService.getItemById(10L)).thenReturn(item);
        when(videoGenerationService.listByCategoryFamily("storyboard_item:10", 7L, null))
                .thenReturn(List.of());

        StoryboardVideoQualityService.QualityReviewResult result =
                service.evaluateStoryboardItem(10L, 7L, true, 70);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.selectedCandidate()).isNull();
        assertThat(result.message()).isNotBlank();
        verify(qualityMapper, never()).insert(any(StoryboardVideoQuality.class));
        verify(storyboardService, never()).updateItem(any());
    }

    @Test
    void selectCandidateMarksOnlyChosenRecordAndBackfillsStoryboardItem() {
        StoryboardVideoQuality first = quality(1L, 10L, "https://cdn.example.com/first.mp4", 81);
        StoryboardVideoQuality second = quality(2L, 10L, "https://cdn.example.com/second.mp4", 92);
        first.setSelected(true);
        store.put(first.getId(), first);
        store.put(second.getId(), second);

        StoryboardVideoQuality selected = service.selectCandidate(10L, 2L);

        assertThat(selected.getId()).isEqualTo(2L);
        assertThat(selected.getSelected()).isTrue();
        assertThat(store.get(1L).getSelected()).isFalse();

        ArgumentCaptor<StoryboardItem> itemCaptor = ArgumentCaptor.forClass(StoryboardItem.class);
        verify(storyboardService).updateItem(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getGeneratedVideoUrl()).isEqualTo("https://cdn.example.com/second.mp4");
        assertThat(itemCaptor.getValue().getVideoPrompt()).isEqualTo(second.getPromptSnapshot());
    }

    @Test
    void selectCandidateByVideoUrlUsesReviewedCandidate() {
        StoryboardVideoQuality quality = quality(5L, 10L, "https://cdn.example.com/url-select.mp4", 88);
        store.put(quality.getId(), quality);
        selectOneQueue.add(quality);

        StoryboardVideoQuality selected =
                service.selectCandidateByVideoUrl(10L, "https://cdn.example.com/url-select.mp4");

        assertThat(selected.getId()).isEqualTo(5L);
        assertThat(selected.getSelected()).isTrue();
        verify(storyboardService).updateItem(any(StoryboardItem.class));
    }

    private void stubQualityMapper() {
        when(qualityMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            StoryboardVideoQuality value = selectOneQueue.pollFirst();
            if (value != null) {
                pendingSelectedQualityId.set(value.getId());
            }
            return value;
        });
        when(qualityMapper.insert(any(StoryboardVideoQuality.class))).thenAnswer(invocation -> {
            StoryboardVideoQuality value = invocation.getArgument(0);
            value.setId(nextId++);
            store.put(value.getId(), value);
            return 1;
        });
        when(qualityMapper.updateById(any(StoryboardVideoQuality.class))).thenAnswer(invocation -> {
            StoryboardVideoQuality value = invocation.getArgument(0);
            store.put(value.getId(), value);
            return 1;
        });
        when(qualityMapper.selectById(any(Long.class))).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            pendingSelectedQualityId.set(id);
            return store.get(id);
        });
        when(qualityMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation ->
                new ArrayList<>(store.values()).stream()
                        .sorted(Comparator
                                .comparing((StoryboardVideoQuality q) -> Boolean.TRUE.equals(q.getSelected()))
                                .thenComparing(q -> q.getTotalScore() == null ? 0 : q.getTotalScore())
                                .reversed())
                        .toList());
        when(qualityMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenAnswer(invocation -> {
            int index = updateCalls.incrementAndGet();
            if (index % 2 == 1) {
                store.values().stream()
                        .filter(q -> q.getStoryboardItemId() != null)
                        .forEach(q -> q.setSelected(false));
            } else {
                Long qualityId = pendingSelectedQualityId.get();
                StoryboardVideoQuality target = store.get(qualityId);
                if (target != null) {
                    target.setSelected(true);
                    target.setReviewerType("manual");
                }
            }
            return 1;
        });
    }

    private StoryboardItem storyboardItem() {
        return StoryboardItem.builder()
                .id(10L)
                .duration(new BigDecimal("5"))
                .characterIds("[1,2]")
                .cameraMovement("push in")
                .dialogue("Hold the door")
                .soundEffect("wind")
                .music("low strings")
                .content("Hero enters the gate")
                .build();
    }

    private VideoTask goodTask() {
        return VideoTask.builder()
                .id(101L)
                .status(2)
                .category("storyboard_item:10")
                .prompt("assetItemId characterLocks referenceOrderPolicy push in dialogue Hold the door sound wind music low strings")
                .generateMode("image2video")
                .referenceImageUrls("[\"https://cdn.example.com/hero.png\",\"https://cdn.example.com/sidekick.png\"]")
                .firstFrameImageUrl("https://cdn.example.com/first.png")
                .lastFrameImageUrl("https://cdn.example.com/last.png")
                .duration(5)
                .generateAudio(true)
                .build();
    }

    private VideoTask weakTask() {
        return VideoTask.builder()
                .id(102L)
                .status(2)
                .category("storyboard_item:10")
                .prompt("wide shot")
                .generateMode("text2video")
                .referenceImageUrls("[]")
                .duration(9)
                .generateAudio(false)
                .build();
    }

    private VideoItem goodVideo() {
        return VideoItem.builder()
                .id(201L)
                .taskId(101L)
                .videoUrl("https://cdn.example.com/good.mp4")
                .coverUrl("https://cdn.example.com/good.jpg")
                .duration(5)
                .fileSize(2048L)
                .status(1)
                .build();
    }

    private VideoItem weakVideo() {
        return VideoItem.builder()
                .id(202L)
                .taskId(102L)
                .videoUrl("https://cdn.example.com/weak.mp4")
                .duration(9)
                .status(1)
                .build();
    }

    private StoryboardVideoQuality quality(Long id, Long storyboardItemId, String videoUrl, int score) {
        return StoryboardVideoQuality.builder()
                .id(id)
                .storyboardItemId(storyboardItemId)
                .videoUrl(videoUrl)
                .promptSnapshot("prompt for " + id)
                .totalScore(score)
                .selected(false)
                .build();
    }
}
