package com.stonewu.fusion.service.storyboard;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.stonewu.fusion.controller.storyboard.vo.ComposeEpisodeVideoReqVO;
import com.stonewu.fusion.entity.storage.StorageConfig;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.mapper.storyboard.StoryboardEpisodeMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardSceneMapper;
import com.stonewu.fusion.service.storage.MediaStorageService;
import com.stonewu.fusion.service.storage.StorageConfigService;
import com.stonewu.fusion.service.task.TaskStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoComposeServiceTests {

    @Mock
    private StoryboardService storyboardService;

    @Mock
    private StoryboardEpisodeMapper episodeMapper;

    @Mock
    private StoryboardSceneMapper sceneMapper;

    @Mock
    private MediaStorageService mediaStorageService;

    @Mock
    private StorageConfigService storageConfigService;

    @Mock
    private TaskStreamService taskStreamService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Executor videoComposeExecutor;

    private VideoComposeService videoComposeService;

    @BeforeEach
    void setUp() {
        videoComposeService = new VideoComposeService(
                storyboardService,
                episodeMapper,
                sceneMapper,
                mediaStorageService,
                storageConfigService,
                taskStreamService,
                cacheManager,
                videoComposeExecutor
        );
        ReflectionTestUtils.setField(videoComposeService, "mediaLocalPath", "D:/media-root");
        ReflectionTestUtils.setField(videoComposeService, "allowedHostsConfig", "");
        ReflectionTestUtils.setField(videoComposeService, "maxRedirects", 3);
        ReflectionTestUtils.setField(videoComposeService, "ffmpegPath", "ffmpeg");
        ReflectionTestUtils.setField(videoComposeService, "ffprobePath", "ffprobe");
    }

    @Test
    void submitComposeUsesConditionalUpdateBeforeScheduling() {
        when(episodeMapper.selectById(11L)).thenReturn(StoryboardEpisode.builder().id(11L).storyboardId(21L).build());
        when(storyboardService.getById(21L)).thenReturn(Storyboard.builder().id(21L).projectId(31L).build());
        when(taskStreamService.createTask(eq(99L), eq(31L), eq("storyboard_episode_compose"), any(String.class), eq("storyboard_episode"), eq(11L), any(String.class)))
            .thenReturn("task-1");
        when(storyboardService.listScenesByEpisode(11L)).thenReturn(List.of());
        String taskId = videoComposeService.submitCompose(11L, 99L);

        assertThat(taskId).isEqualTo("task-1");
        verify(taskStreamService).fail("task-1", "本集没有可合成的视频，请先生成镜头视频");
        verifyNoInteractions(videoComposeExecutor);
    }

    @Test
    void submitComposeUsesConditionalUpdateBeforeSchedulingWhenVideoExists() {
        when(episodeMapper.selectById(11L)).thenReturn(StoryboardEpisode.builder()
            .id(11L)
            .storyboardId(21L)
            .episodeNumber(1)
            .title("第一集")
            .build());
        when(storyboardService.getById(21L)).thenReturn(Storyboard.builder().id(21L).projectId(31L).build());
        when(taskStreamService.createTask(eq(99L), eq(31L), eq("storyboard_episode_compose"), any(String.class), eq("storyboard_episode"), eq(11L), any(String.class)))
            .thenReturn("task-1");
        when(storyboardService.listScenesByEpisode(11L)).thenReturn(List.of(
                com.stonewu.fusion.entity.storyboard.StoryboardScene.builder().id(101L).sortOrder(0).build()
        ));
        when(storyboardService.listItemsByScene(101L)).thenReturn(List.of(
                com.stonewu.fusion.entity.storyboard.StoryboardItem.builder().id(201L).sortOrder(0).videoUrl("/media/videos/demo.mp4").build()
        ));
        when(episodeMapper.update(eq(null), any(UpdateWrapper.class))).thenReturn(1);

        String taskId = videoComposeService.submitCompose(11L, 99L);

        assertThat(taskId).isEqualTo("task-1");
        verify(episodeMapper).update(eq(null), any(UpdateWrapper.class));
        verify(videoComposeExecutor).execute(any(Runnable.class));
    }

    @Test
    void submitSceneComposeUsesSceneScopeWhenVideoExists() {
        when(sceneMapper.selectById(101L)).thenReturn(StoryboardScene.builder()
                .id(101L)
                .episodeId(11L)
                .storyboardId(21L)
                .sceneHeading("客厅")
                .build());
        when(storyboardService.getById(21L)).thenReturn(Storyboard.builder().id(21L).projectId(31L).build());
        when(taskStreamService.createTask(eq(99L), eq(31L), eq("storyboard_scene_compose"), any(String.class), eq("storyboard_scene"), eq(101L), any(String.class)))
                .thenReturn("task-scene-1");
        when(storyboardService.listItemsByScene(101L)).thenReturn(List.of(
                com.stonewu.fusion.entity.storyboard.StoryboardItem.builder().id(201L).sortOrder(0).videoUrl("/media/videos/demo.mp4").build()
        ));
        when(sceneMapper.update(eq(null), any(UpdateWrapper.class))).thenReturn(1);

        String taskId = videoComposeService.submitSceneCompose(101L, 99L);

        assertThat(taskId).isEqualTo("task-scene-1");
        verify(sceneMapper).update(eq(null), any(UpdateWrapper.class));
        verify(videoComposeExecutor).execute(any(Runnable.class));
    }

    @Test
    void composeOptionsBurnSubtitlesByDefault() {
        VideoComposeService.ComposeOptions defaults = VideoComposeService.ComposeOptions.defaults();
        VideoComposeService.ComposeOptions emptyRequest = VideoComposeService.ComposeOptions.from(
                new ComposeEpisodeVideoReqVO()
        );

        assertThat(defaults.generateSubtitleFiles()).isTrue();
        assertThat(defaults.burnSubtitles()).isTrue();
        assertThat(emptyRequest.burnSubtitles()).isTrue();
    }

    @Test
    void submitEditedComposeAcceptsOrderedTrimmedClips() {
        when(episodeMapper.selectById(11L)).thenReturn(StoryboardEpisode.builder().id(11L).storyboardId(21L).build());
        when(storyboardService.getById(21L)).thenReturn(Storyboard.builder().id(21L).projectId(31L).build());
        when(storyboardService.listScenesByEpisode(11L)).thenReturn(List.of(
                StoryboardScene.builder().id(101L).sortOrder(0).build()));
        when(storyboardService.listItemsByScene(101L)).thenReturn(List.of(
                StoryboardItem.builder().id(201L).sortOrder(0).duration(BigDecimal.valueOf(5))
                        .videoUrl("/media/videos/a.mp4").build(),
                StoryboardItem.builder().id(202L).sortOrder(1).duration(BigDecimal.valueOf(6))
                        .videoUrl("/media/videos/b.mp4").build()));
        when(taskStreamService.createTask(eq(99L), eq(31L), eq("storyboard_episode_compose"),
                any(String.class), eq("storyboard_episode"), eq(11L), any(String.class))).thenReturn("task-edit");
        when(episodeMapper.update(eq(null), any(UpdateWrapper.class))).thenReturn(1);

        ComposeEpisodeVideoReqVO.EditorClip second = new ComposeEpisodeVideoReqVO.EditorClip();
        second.setItemId(202L);
        second.setSourceStart(1.25);
        second.setDuration(2.5);
        ComposeEpisodeVideoReqVO.EditorClip first = new ComposeEpisodeVideoReqVO.EditorClip();
        first.setItemId(201L);
        first.setDuration(4.0);

        String taskId = videoComposeService.submitEditedCompose(11L, 99L,
                VideoComposeService.ComposeOptions.defaults(), List.of(second, first));

        assertThat(taskId).isEqualTo("task-edit");
        verify(videoComposeExecutor).execute(any(Runnable.class));
    }

    @Test
    void subtitleLayoutFollowsPortraitVideoDimensions() {
        VideoComposeService.VideoDimensions dimensions = VideoComposeService.parseVideoDimensions("720x1280\n");
        VideoComposeService.SubtitleLayout layout = VideoComposeService.resolveSubtitleLayout(dimensions);

        assertThat(dimensions).isEqualTo(new VideoComposeService.VideoDimensions(720, 1280));
        assertThat(layout.playResX()).isEqualTo(720);
        assertThat(layout.playResY()).isEqualTo(1280);
        assertThat(layout.fontSize()).isEqualTo(36);
        assertThat(layout.horizontalMargin()).isEqualTo(36);
        assertThat(layout.verticalMargin()).isEqualTo(83);
        assertThat(layout.maxLineDisplayWidth()).isEqualTo(32);
    }

    @Test
    void wrapSubtitleTextWrapsChineseAndLongEnglishTokensWithoutLosingText() {
        String chinese = "字幕".repeat(20);
        String wrappedChinese = VideoComposeService.wrapSubtitleText(chinese, 32);
        String wrappedEnglish = VideoComposeService.wrapSubtitleText("alpha beta gamma delta", 14);
        String longToken = "abcdefghijklmnopqrstuvwxyz0123456789";
        String wrappedLongToken = VideoComposeService.wrapSubtitleText(longToken, 10);
        String wrappedWideToken = VideoComposeService.wrapSubtitleText("W".repeat(12), 10);

        assertThat(wrappedChinese).contains("\n");
        assertThat(wrappedChinese.replace("\n", "")).isEqualTo(chinese);
        assertThat(wrappedChinese.split("\n"))
                .allMatch(line -> line.codePointCount(0, line.length()) <= 16);
        assertThat(wrappedEnglish).isEqualTo("alpha beta\ngamma delta");
        assertThat(wrappedLongToken).contains("\n");
        assertThat(wrappedLongToken.replace("\n", "")).isEqualTo(longToken);
        assertThat(wrappedLongToken.split("\n"))
                .allMatch(line -> line.codePointCount(0, line.length()) <= 10);
        assertThat(wrappedWideToken.split("\n"))
                .allMatch(line -> line.length() <= 5);
    }

    @Test
    void wrapSubtitleTextPreservesManualBreaksAndEmojiGraphemes() {
        String familyEmoji = "👨‍👩‍👧‍👦";
        String text = "手动第一行\n甲" + familyEmoji + "乙丙丁戊己庚辛";

        String wrapped = VideoComposeService.wrapSubtitleText(text, 12);

        assertThat(wrapped).startsWith("手动第一行\n");
        assertThat(wrapped).contains(familyEmoji);
        assertThat(wrapped.replace("\n", "")).isEqualTo(text.replace("\n", ""));
    }

    @Test
    void buildSubtitleFilesWritesWrappedSrtAndAssForPortraitVideo() throws IOException {
        String dialogue = "字幕".repeat(20);
        Path tempDir = Path.of("target", "subtitle-layout-test");
        Files.createDirectories(tempDir);
        when(storyboardService.listItemsByScene(101L)).thenReturn(List.of(
                StoryboardItem.builder()
                        .id(201L)
                        .sortOrder(0)
                        .videoUrl("/media/videos/demo.mp4")
                        .duration(BigDecimal.valueOf(5))
                        .dialogue(dialogue)
                        .build()
        ));
        List<?> clips = ReflectionTestUtils.invokeMethod(videoComposeService, "collectSceneComposeClips", 101L);

        ReflectionTestUtils.invokeMethod(
                videoComposeService,
                "buildSubtitleFiles",
                tempDir,
                clips,
                new VideoComposeService.VideoDimensions(720, 1280)
        );

        String wrapped = VideoComposeService.wrapSubtitleText(dialogue, 32);
        String srt = Files.readString(tempDir.resolve("subtitles.srt"));
        String ass = Files.readString(tempDir.resolve("subtitles.ass"));
        assertThat(srt).contains(wrapped);
        assertThat(ass)
                .contains("PlayResX: 720")
                .contains("PlayResY: 1280")
                .contains("WrapStyle: 0")
                .contains(wrapped.replace("\n", "\\N"));
    }

    @Test
    void submitComposeMarksFailedWhenExecutorRejectsTask() {
        when(episodeMapper.selectById(11L)).thenReturn(StoryboardEpisode.builder().id(11L).storyboardId(21L).build());
        when(storyboardService.getById(21L)).thenReturn(Storyboard.builder().id(21L).projectId(31L).build());
        when(taskStreamService.createTask(eq(99L), eq(31L), eq("storyboard_episode_compose"), any(String.class), eq("storyboard_episode"), eq(11L), any(String.class)))
            .thenReturn("task-1");
        when(storyboardService.listScenesByEpisode(11L)).thenReturn(List.of(
                com.stonewu.fusion.entity.storyboard.StoryboardScene.builder().id(101L).sortOrder(0).build()
        ));
        when(storyboardService.listItemsByScene(101L)).thenReturn(List.of(
                com.stonewu.fusion.entity.storyboard.StoryboardItem.builder().id(201L).sortOrder(0).videoUrl("/media/videos/demo.mp4").build()
        ));
        when(episodeMapper.update(eq(null), any(UpdateWrapper.class))).thenReturn(1);
        org.mockito.Mockito.doThrow(new RejectedExecutionException("busy"))
                .when(videoComposeExecutor)
                .execute(any(Runnable.class));

        String taskId = videoComposeService.submitCompose(11L, 99L);

        assertThat(taskId).isEqualTo("task-1");
        verify(episodeMapper, org.mockito.Mockito.times(2)).update(eq(null), any(UpdateWrapper.class));
        verify(taskStreamService).fail("task-1", "合成队列繁忙，请稍后重试");
    }

    @Test
    void validateRemoteUriRejectsLoopbackAddress() {
        assertThatThrownBy(() -> invokePrivate("validateRemoteUri", java.net.URI.create("http://127.0.0.1/test.mp4")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("拒绝访问内网或本地地址");
    }

    @Test
    void resolveManagedMediaPathRejectsPathTraversal() {
        assertThatThrownBy(() -> invokePrivate("resolveManagedMediaPath", "/media/../../windows/system32/config/sam"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("非法媒体相对路径");
    }

    @Test
    void resolveManagedMediaPathPrefersDefaultLocalStorageBasePath() throws Throwable {
        when(storageConfigService.getDefaultConfig()).thenReturn(StorageConfig.builder()
                .type("local")
                .basePath("D:/configured-media")
                .build());

        Object resolved = invokePrivate("resolveManagedMediaPath", "/media/videos/test.mp4");

        assertThat(resolved)
            .isEqualTo(java.nio.file.Paths.get("D:/configured-media/videos/test.mp4").toAbsolutePath().normalize());
    }

    @Test
    void resolveErrorMessageReturnsReadableHintWhenFfmpegExecutableMissing() throws Throwable {
        ReflectionTestUtils.setField(videoComposeService, "ffmpegPath", "C:/ffmpeg/bin/ffmpeg.exe");

        Method method = VideoComposeService.class.getDeclaredMethod("resolveErrorMessage", Throwable.class);
        method.setAccessible(true);
        String message = (String) method.invoke(
            videoComposeService,
            new IOException("Cannot run program \"ffmpeg\": CreateProcess error=2, 系统找不到指定的文件。")
        );

        assertThat(message)
                .contains("未找到 ffmpeg 可执行文件")
                .contains("video.compose.ffmpeg-path")
                .contains("C:/ffmpeg/bin/ffmpeg.exe");
    }

    private Object invokePrivate(String methodName, Object argument) throws Throwable {
        Method method = VideoComposeService.class.getDeclaredMethod(methodName, argument.getClass());
        method.setAccessible(true);
        try {
            return method.invoke(videoComposeService, argument);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
