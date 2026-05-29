package com.stonewu.fusion.service.storyboard;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.mapper.storyboard.StoryboardEpisodeMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardItemMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardSceneMapper;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.storage.MediaStorageService;
import com.stonewu.fusion.service.team.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StoryboardServiceTests {

    private StoryboardMapper storyboardMapper;
    private StoryboardItemMapper itemMapper;
    private VideoGenerationService videoGenerationService;
    private StoryboardService storyboardService;

    @BeforeEach
    void setUp() {
        storyboardMapper = mock(StoryboardMapper.class);
        itemMapper = mock(StoryboardItemMapper.class);
        videoGenerationService = mock(VideoGenerationService.class);
        storyboardService = new StoryboardService(
                storyboardMapper,
                mock(StoryboardEpisodeMapper.class),
                mock(StoryboardSceneMapper.class),
                itemMapper,
                mock(TeamService.class),
                videoGenerationService,
                mock(MediaStorageService.class)
        );
    }

    @Test
    void attachUploadedVideoCreatesCompletedTaskAndVideoItem() {
        StoryboardItem item = StoryboardItem.builder()
                .id(101L)
                .storyboardId(201L)
                .duration(new BigDecimal("5.4"))
                .content("shot content")
                .build();
        when(itemMapper.selectById(101L)).thenReturn(item);
        when(storyboardMapper.selectById(201L)).thenReturn(Storyboard.builder()
                .id(201L)
                .projectId(301L)
                .build());
        when(videoGenerationService.create(any(VideoTask.class))).thenAnswer(invocation -> {
            VideoTask task = invocation.getArgument(0);
            task.setId(401L);
            return task;
        });

        StoryboardItem updated = storyboardService.attachUploadedVideo(
                101L,
                "/media/videos/uploaded.mp4",
                501L,
                1024L
        );

        assertThat(updated.getVideoUrl()).isEqualTo("/media/videos/uploaded.mp4");

        ArgumentCaptor<VideoTask> taskCaptor = ArgumentCaptor.forClass(VideoTask.class);
        verify(videoGenerationService).create(taskCaptor.capture());
        VideoTask task = taskCaptor.getValue();
        assertThat(task.getTaskId()).startsWith("manual_");
        assertThat(task.getUserId()).isEqualTo(501L);
        assertThat(task.getProjectId()).isEqualTo(301L);
        assertThat(task.getGenerateMode()).isEqualTo("manual_upload");
        assertThat(task.getStatus()).isEqualTo(2);
        assertThat(task.getSuccessCount()).isEqualTo(1);
        assertThat(task.getCount()).isEqualTo(1);
        assertThat(task.getCategory()).isEqualTo("storyboard_item:101:manual");
        assertThat(task.getPrompt()).isEqualTo("shot content");

        ArgumentCaptor<VideoItem> itemCaptor = ArgumentCaptor.forClass(VideoItem.class);
        verify(videoGenerationService).createItem(itemCaptor.capture());
        VideoItem videoItem = itemCaptor.getValue();
        assertThat(videoItem.getTaskId()).isEqualTo(401L);
        assertThat(videoItem.getVideoUrl()).isEqualTo("/media/videos/uploaded.mp4");
        assertThat(videoItem.getFileSize()).isEqualTo(1024L);
        assertThat(videoItem.getDuration()).isEqualTo(5);
        assertThat(videoItem.getStatus()).isEqualTo(1);

        verify(itemMapper).updateById(item);
    }

    @Test
    void attachUploadedVideoRejectsItemThatAlreadyHasVideo() {
        StoryboardItem item = StoryboardItem.builder()
                .id(101L)
                .storyboardId(201L)
                .videoUrl("/media/videos/existing.mp4")
                .build();
        when(itemMapper.selectById(101L)).thenReturn(item);

        assertThatThrownBy(() -> storyboardService.attachUploadedVideo(
                101L,
                "/media/videos/uploaded.mp4",
                501L,
                1024L
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有视频");

        verifyNoInteractions(videoGenerationService);
    }
}
