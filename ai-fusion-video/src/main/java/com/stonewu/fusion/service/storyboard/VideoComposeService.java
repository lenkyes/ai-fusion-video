package com.stonewu.fusion.service.storyboard;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.stonewu.fusion.common.BusinessException;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.IDN;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.BreakIterator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按集合成视频服务。
 * <p>
 * 流程：取该集所有场次的所有镜头视频（按 sortOrder 排序），下载到临时目录，
 * 用 ffmpeg concat 拼接（先尝试 demuxer 零转码；失败则 fallback 到 filter_complex 重新编码），
 * 然后通过 MediaStorageService 持久化，更新 episode 的合成状态与URL。
 */
@Service
@Slf4j
public class VideoComposeService {

    private static final String LOCAL_MEDIA_PUBLIC_PREFIX = "/media/";
    private static final String EPISODE_TASK_TYPE = "storyboard_episode_compose";
    private static final String EPISODE_TASK_CONTEXT_TYPE = "storyboard_episode";
    private static final String SCENE_TASK_TYPE = "storyboard_scene_compose";
    private static final String SCENE_TASK_CONTEXT_TYPE = "storyboard_scene";
    private static final String TASK_INITIAL_MESSAGE = "已提交合成任务，正在拼接镜头视频…";

    public static final int STATUS_IDLE = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_FAILED = 3;

    private static final double DEFAULT_CLIP_DURATION_SECONDS = 5.0;
    private static final double DEFAULT_ORIGINAL_AUDIO_VOLUME = 1.0;
    private static final double DEFAULT_BGM_VOLUME = 0.25;
    private static final double MAX_AUDIO_VOLUME = 2.0;
    private static final VideoDimensions DEFAULT_VIDEO_DIMENSIONS = new VideoDimensions(1920, 1080);
    private static final int MAX_PROBED_VIDEO_DIMENSION = 32768;
    private static final double SUBTITLE_FONT_SIZE_RATIO = 0.05;
    private static final double SUBTITLE_HORIZONTAL_MARGIN_RATIO = 0.05;
    private static final double SUBTITLE_VERTICAL_MARGIN_RATIO = 0.065;
    private static final double SUBTITLE_LINE_WIDTH_SAFETY_RATIO = 0.9;
    private static final Pattern SUBTITLE_GRAPHEME_PATTERN = Pattern.compile("\\X");

    private final StoryboardService storyboardService;
    private final StoryboardEpisodeMapper episodeMapper;
    private final StoryboardSceneMapper sceneMapper;
    private final MediaStorageService mediaStorageService;
    private final StorageConfigService storageConfigService;
    private final TaskStreamService taskStreamService;
    private final CacheManager cacheManager;
    private final Executor videoComposeExecutor;

    @Value("${app.storage.local-base-path:./data/media}")
    private String mediaLocalPath;

    @Value("${video.compose.allowed-hosts:}")
    private String allowedHostsConfig;

    @Value("${video.compose.max-redirects:3}")
    private int maxRedirects;

    @Value("${video.compose.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${video.compose.ffprobe-path:ffprobe}")
    private String ffprobePath;

    public VideoComposeService(StoryboardService storyboardService,
                               StoryboardEpisodeMapper episodeMapper,
                               StoryboardSceneMapper sceneMapper,
                               MediaStorageService mediaStorageService,
                               StorageConfigService storageConfigService,
                               TaskStreamService taskStreamService,
                               CacheManager cacheManager,
                               @Qualifier("videoComposeExecutor") Executor videoComposeExecutor) {
        this.storyboardService = storyboardService;
        this.episodeMapper = episodeMapper;
        this.sceneMapper = sceneMapper;
        this.mediaStorageService = mediaStorageService;
        this.storageConfigService = storageConfigService;
        this.taskStreamService = taskStreamService;
        this.cacheManager = cacheManager;
        this.videoComposeExecutor = videoComposeExecutor;
    }

    /**
     * 提交合成任务。同步标记状态为 RUNNING，异步执行实际合成。
     * 若已在合成中则抛出异常。
     */
    public String submitCompose(Long episodeId, Long userId) {
        return submitCompose(episodeId, userId, ComposeOptions.defaults());
    }

    public String submitCompose(Long episodeId, Long userId, ComposeOptions options) {
        StoryboardEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException(404, "分镜集不存在: " + episodeId);
        }

        Storyboard storyboard = storyboardService.getById(episode.getStoryboardId());
        if (storyboard == null) {
            throw new BusinessException(404, "分镜不存在: " + episode.getStoryboardId());
        }

        ComposeTarget target = new ComposeTarget(
                ComposeTargetType.EPISODE,
                episodeId,
                storyboard.getProjectId(),
                episode.getStoryboardId(),
                episodeId,
                EPISODE_TASK_TYPE,
                EPISODE_TASK_CONTEXT_TYPE,
                buildEpisodeTaskTitle(episode),
                "本集没有可合成的视频，请先生成镜头视频",
                "本集已在合成中，请稍候",
                "episode",
                "ep"
        );
        return submitTarget(target, userId, collectEpisodeComposeClips(episodeId), options);
    }

    public String submitEditedCompose(Long episodeId, Long userId, ComposeOptions options,
                                      List<ComposeEpisodeVideoReqVO.EditorClip> requestedClips) {
        if (requestedClips == null || requestedClips.isEmpty()) {
            return submitCompose(episodeId, userId, options);
        }
        StoryboardEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException(404, "分镜集不存在: " + episodeId);
        }
        Storyboard storyboard = storyboardService.getById(episode.getStoryboardId());
        if (storyboard == null) {
            throw new BusinessException(404, "分镜不存在: " + episode.getStoryboardId());
        }
        List<ComposeClip> available = collectEpisodeComposeClips(episodeId);
        List<ComposeClip> edited = new ArrayList<>();
        List<EditorAudio> editorAudios = new ArrayList<>();
        String primaryVideoTrackId = requestedClips.stream()
                .filter(Objects::nonNull)
                .filter(clip -> !"audio".equalsIgnoreCase(clip.getTrackType()))
                .map(ComposeEpisodeVideoReqVO.EditorClip::getTrackId)
                .filter(StringUtils::hasText)
                .filter("video-1"::equals)
                .findFirst()
                .orElseGet(() -> requestedClips.stream()
                        .filter(Objects::nonNull)
                        .filter(clip -> "video".equalsIgnoreCase(clip.getTrackType()))
                        .map(ComposeEpisodeVideoReqVO.EditorClip::getTrackId)
                        .filter(StringUtils::hasText)
                        .findFirst()
                        .orElse(null));
        for (ComposeEpisodeVideoReqVO.EditorClip requested : requestedClips) {
            if (requested == null) {
                throw new BusinessException("剪辑片段不能为空");
            }
            if ((requested.getItemId() == null || requested.getItemId() <= 0)
                    && StringUtils.hasText(requested.getSourceUrl())) {
                String sourceUrl = requested.getSourceUrl().trim();
                if (!sourceUrl.startsWith(LOCAL_MEDIA_PUBLIC_PREFIX) && !isAbsoluteHttpUrl(sourceUrl)) {
                    throw new BusinessException("编辑器素材地址无效");
                }
                StoryboardItem uploaded = StoryboardItem.builder().id(-1L).duration(BigDecimal.valueOf(
                        finiteDuration(requested.getDuration(), DEFAULT_CLIP_DURATION_SECONDS))).build();
                if ("audio".equalsIgnoreCase(requested.getTrackType())) {
                    if (!Boolean.TRUE.equals(requested.getTrackMuted())) {
                        editorAudios.add(new EditorAudio(sourceUrl,
                                finitePositiveOrZero(requested.getSourceStart()),
                                finiteDuration(requested.getDuration(), DEFAULT_CLIP_DURATION_SECONDS),
                                finitePositiveOrZero(requested.getTimelineStart()),
                                clampTrackVolume(requested.getTrackVolume())));
                    }
                    continue;
                }
                if (primaryVideoTrackId != null && !primaryVideoTrackId.equals(requested.getTrackId())) {
                    continue;
                }
                edited.add(new ComposeClip(uploaded, sourceUrl,
                        finitePositiveOrZero(requested.getSourceStart()),
                        finiteDuration(requested.getDuration(), DEFAULT_CLIP_DURATION_SECONDS),
                        !Boolean.TRUE.equals(requested.getTrackMuted()),
                        clampTrackVolume(requested.getTrackVolume())));
                continue;
            }
            if (requested.getItemId() == null) throw new BusinessException("剪辑片段缺少 itemId");
            ComposeClip source = available.stream()
                    .filter(clip -> requested.getItemId().equals(clip.item().getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("剪辑片段不属于当前分集: " + requested.getItemId()));
            double start = finitePositiveOrZero(requested.getSourceStart());
            double duration = finiteDuration(requested.getDuration(), resolveClipDurationSeconds(source.item()));
            if (!"audio".equalsIgnoreCase(requested.getTrackType())) {
                if (primaryVideoTrackId != null && !primaryVideoTrackId.equals(requested.getTrackId())) {
                    continue;
                }
                edited.add(new ComposeClip(source.item(), source.videoUrl(), start, duration,
                        !Boolean.TRUE.equals(requested.getTrackMuted()),
                        clampTrackVolume(requested.getTrackVolume())));
            }
        }
        ComposeTarget target = new ComposeTarget(ComposeTargetType.EPISODE, episodeId, storyboard.getProjectId(),
                episode.getStoryboardId(), episodeId, EPISODE_TASK_TYPE, EPISODE_TASK_CONTEXT_TYPE,
                buildEpisodeTaskTitle(episode), "剪辑工程没有可合成的视频", "本集已在合成中，请稍候", "episode", "ep");
        ComposeOptions baseOptions = options != null ? options : ComposeOptions.defaults();
        ComposeOptions effectiveOptions = editorAudios.isEmpty() ? baseOptions : baseOptions.withEditorAudios(editorAudios);
        return submitTarget(target, userId, edited, effectiveOptions);
    }

    public String submitSceneCompose(Long sceneId, Long userId) {
        return submitSceneCompose(sceneId, userId, ComposeOptions.defaults());
    }

    public String submitSceneCompose(Long sceneId, Long userId, ComposeOptions options) {
        StoryboardScene scene = sceneMapper.selectById(sceneId);
        if (scene == null) {
            throw new BusinessException(404, "分镜场次不存在: " + sceneId);
        }

        Storyboard storyboard = storyboardService.getById(scene.getStoryboardId());
        if (storyboard == null) {
            throw new BusinessException(404, "分镜不存在: " + scene.getStoryboardId());
        }

        ComposeTarget target = new ComposeTarget(
                ComposeTargetType.SCENE,
                sceneId,
                storyboard.getProjectId(),
                scene.getStoryboardId(),
                scene.getEpisodeId(),
                SCENE_TASK_TYPE,
                SCENE_TASK_CONTEXT_TYPE,
                buildSceneTaskTitle(scene),
                "当前场次没有可合成的视频，请先生成镜头视频",
                "当前场次已在合成中，请稍候",
                "scene",
                "scene"
        );
        return submitTarget(target, userId, collectSceneComposeClips(sceneId), options);
    }

    private String submitTarget(ComposeTarget target, Long userId, List<ComposeClip> clips, ComposeOptions options) {
        ComposeOptions effectiveOptions = options != null ? options : ComposeOptions.defaults();
        String taskId = taskStreamService.createTask(
                userId,
                target.projectId(),
                target.taskType(),
                target.taskTitle(),
                target.taskContextType(),
                target.id(),
                TASK_INITIAL_MESSAGE
        );

        if (clips.isEmpty()) {
            String message = target.noClipsMessage();
            markFailed(target, message);
            taskStreamService.fail(taskId, message);
            return taskId;
        }

        int updated = markRunning(target);
        if (updated == 0) {
            taskStreamService.fail(taskId, target.busyMessage());
            return taskId;
        }

        try {
            videoComposeExecutor.execute(() -> {
                try {
                    doCompose(target, taskId, clips, effectiveOptions);
                } catch (Throwable t) {
                    String errorMessage = resolveErrorMessage(t);
                    log.error("[VideoCompose] 合成失败: {}Id={}", target.logLabel(), target.id(), t);
                    markFailed(target, errorMessage);
                    taskStreamService.fail(taskId, errorMessage);
                }
            });
        } catch (RejectedExecutionException e) {
            String message = "合成队列繁忙，请稍后重试";
            markFailed(target, message);
            taskStreamService.fail(taskId, message);
        }
        return taskId;
    }

    private void doCompose(ComposeTarget target, String taskId, List<ComposeClip> clips,
                           ComposeOptions options) throws Exception {
        log.info("[VideoCompose] 开始合成 {}Id={}, taskId={}", target.logLabel(), target.id(), taskId);
        long startMs = System.currentTimeMillis();
        log.info("[VideoCompose] {}Id={}, 待合成视频数={}, options={}", target.logLabel(), target.id(), clips.size(), options);

        Path workDir = Files.createTempDirectory("compose_" + target.workDirPrefix() + "_" + target.id() + "_");
        try {
            List<Path> localFiles = new ArrayList<>();
            for (int i = 0; i < clips.size(); i++) {
                Path local = workDir.resolve(String.format("v%04d.mp4", i));
                ComposeClip clip = clips.get(i);
                Path downloaded = workDir.resolve(String.format("source%04d.mp4", i));
                downloadToFile(clip.videoUrl(), downloaded);
                if (clip.requiresProcessing()) {
                    if (!runFfmpegTrim(downloaded, local, clip.sourceStart(), clip.duration(),
                            clip.audioEnabled(), clip.audioVolume())) {
                        throw new RuntimeException("ffmpeg 裁剪片段失败: " + clip.item().getId());
                    }
                } else {
                    Files.move(downloaded, local, StandardCopyOption.REPLACE_EXISTING);
                }
                localFiles.add(local);
            }

            Path listFile = workDir.resolve("list.txt");
            StringBuilder sb = new StringBuilder();
            for (Path f : localFiles) {
                String s = f.toAbsolutePath().toString().replace("'", "'\\''");
                sb.append("file '").append(s).append("'\n");
            }
            Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);

            Path output = workDir.resolve("output.mp4");
            boolean ok = runFfmpegConcatDemuxer(listFile, output);
            if (!ok) {
                log.warn("[VideoCompose] concat demuxer 失败，回退到 filter_complex {}Id={}", target.logLabel(), target.id());
                ok = runFfmpegFilterConcat(localFiles, output);
            }
            if (!ok || !Files.exists(output) || Files.size(output) == 0) {
                throw new RuntimeException("ffmpeg 合成失败（concat 与 filter 均失败）");
            }

            VideoDimensions videoDimensions = probeVideoDimensions(output);
            SubtitleFiles subtitleFiles = buildSubtitleFiles(workDir, clips, videoDimensions);

            Path bgmFile = null;
            if (StringUtils.hasText(options.bgmUrl())) {
                bgmFile = workDir.resolve("bgm" + resolveMediaExtension(options.bgmUrl(), "mp3"));
                downloadToFile(options.bgmUrl(), bgmFile);
            }
            List<EditorAudioFile> editorAudioFiles = new ArrayList<>();
            for (int i = 0; i < options.editorAudios().size(); i++) {
                EditorAudio audio = options.editorAudios().get(i);
                Path audioFile = workDir.resolve("editor_audio_" + i + resolveMediaExtension(audio.url(), "mp3"));
                downloadToFile(audio.url(), audioFile);
                editorAudioFiles.add(new EditorAudioFile(audioFile, audio));
            }

            Path finalOutput = output;
            double outputDuration = clips.stream().mapToDouble(ComposeClip::duration).sum();
            boolean shouldBurnSubtitles = options.burnSubtitles() && subtitleFiles.hasSubtitle();
            if (shouldBurnSubtitles || bgmFile != null || !editorAudioFiles.isEmpty()
                    || !options.keepOriginalAudio()
                    || !isDefaultVolume(options.originalAudioVolume())
                    || options.fadeInDuration() > 0 || options.fadeOutDuration() > 0) {
                Path processed = workDir.resolve("output_processed.mp4");
                ok = runFfmpegPostProcess(output, shouldBurnSubtitles ? subtitleFiles.assFile() : null,
                        bgmFile, editorAudioFiles, processed, outputDuration, options);
                if (!ok || !Files.exists(processed) || Files.size(processed) == 0) {
                    throw new RuntimeException("ffmpeg 后处理失败（字幕/音频混合）");
                }
                finalOutput = processed;
            }

            String storedUrl = mediaStorageService.storeFile(finalOutput, "videos/composed", "mp4");
            log.info("[VideoCompose] 已保存到存储: {}", storedUrl);
            String srtUrl = null;
            String assUrl = null;
            if (options.generateSubtitleFiles() && subtitleFiles.hasSubtitle()) {
                srtUrl = mediaStorageService.storeFile(subtitleFiles.srtFile(), "subtitles", "srt");
                assUrl = mediaStorageService.storeFile(subtitleFiles.assFile(), "subtitles", "ass");
                log.info("[VideoCompose] 已保存外挂字幕: srt={}, ass={}", srtUrl, assUrl);
            }

            markDone(target, storedUrl, srtUrl, assUrl);

            String subtitleText = StringUtils.hasText(srtUrl) ? " · 字幕：" + srtUrl : "";
            taskStreamService.complete(taskId, "✓ 合成完成 · 视频地址：" + storedUrl + subtitleText);

            log.info("[VideoCompose] 完成 {}Id={}, 耗时={}ms, 视频数={}",
                    target.logLabel(), target.id(), System.currentTimeMillis() - startMs, clips.size());
        } finally {
            try {
                FileSystemUtils.deleteRecursively(workDir.toFile());
            } catch (Exception e) {
                log.warn("[VideoCompose] 临时目录清理失败 {}", workDir, e);
            }
        }
    }

    private String buildEpisodeTaskTitle(StoryboardEpisode episode) {
        String episodeLabel = StringUtils.hasText(episode.getTitle())
                ? episode.getTitle().trim()
                : (episode.getEpisodeNumber() != null
                ? "第 " + episode.getEpisodeNumber() + " 集"
                : "集 " + episode.getId());
        return "合成本集总视频：" + episodeLabel;
    }

    private String buildSceneTaskTitle(StoryboardScene scene) {
        String sceneLabel = StringUtils.hasText(scene.getSceneHeading())
                ? scene.getSceneHeading().trim()
                : (StringUtils.hasText(scene.getSceneNumber())
                ? "场次 " + scene.getSceneNumber()
                : "场次 " + scene.getId());
        return "合成场次视频：" + sceneLabel;
    }

    private String resolveErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "合成失败";
        }
        if (isExecutableMissing(throwable)) {
            return buildMissingExecutableMessage("ffmpeg", "video.compose.ffmpeg-path", getFfmpegExecutable());
        }
        if (StringUtils.hasText(throwable.getMessage())) {
            return throwable.getMessage();
        }
        return "合成失败";
    }

    private List<ComposeClip> collectEpisodeComposeClips(Long episodeId) {
        List<StoryboardScene> scenes = new ArrayList<>(storyboardService.listScenesByEpisode(episodeId));
        scenes.sort(Comparator.comparing(s -> Optional.ofNullable(s.getSortOrder()).orElse(0)));

        List<ComposeClip> clips = new ArrayList<>();
        for (StoryboardScene scene : scenes) {
            clips.addAll(collectSceneComposeClips(scene.getId()));
        }
        return clips;
    }

    private List<ComposeClip> collectSceneComposeClips(Long sceneId) {
        List<StoryboardItem> items = new ArrayList<>(storyboardService.listItemsByScene(sceneId));
        items.sort(Comparator.comparing(i -> Optional.ofNullable(i.getSortOrder()).orElse(0)));

        List<ComposeClip> clips = new ArrayList<>();
        for (StoryboardItem item : items) {
            String url = StringUtils.hasText(item.getVideoUrl())
                    ? item.getVideoUrl()
                    : item.getGeneratedVideoUrl();
            if (StringUtils.hasText(url)) {
                clips.add(new ComposeClip(item, url, 0, resolveClipDurationSeconds(item), true, 1.0));
            }
        }
        return clips;
    }

    private SubtitleFiles buildSubtitleFiles(Path workDir, List<ComposeClip> clips,
                                              VideoDimensions videoDimensions) throws IOException {
        Path srtFile = workDir.resolve("subtitles.srt");
        Path assFile = workDir.resolve("subtitles.ass");
        SubtitleLayout layout = resolveSubtitleLayout(videoDimensions);

        StringBuilder srt = new StringBuilder();
        StringBuilder ass = new StringBuilder();
        ass.append("[Script Info]\n")
                .append("ScriptType: v4.00+\n")
                .append("PlayResX: ").append(layout.playResX()).append('\n')
                .append("PlayResY: ").append(layout.playResY()).append('\n')
                .append("WrapStyle: 0\n")
                .append("ScaledBorderAndShadow: yes\n\n")
                .append("[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, ")
                .append("Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, ")
                .append("Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Default,Arial,").append(layout.fontSize())
                .append(",&H00FFFFFF,&H00FFFFFF,&H80000000,&H80000000,")
                .append("0,0,0,0,100,100,0,0,1,2,0,2,")
                .append(layout.horizontalMargin()).append(',')
                .append(layout.horizontalMargin()).append(',')
                .append(layout.verticalMargin()).append(",1\n\n")
                .append("[Events]\n")
                .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");

        boolean hasSubtitle = false;
        double cursorSeconds = 0;
        int subtitleIndex = 1;
        for (ComposeClip clip : clips) {
            double durationSeconds = clip.duration();
            String text = cleanDialogue(clip.item().getDialogue());
            if (StringUtils.hasText(text)) {
                String wrappedText = wrapSubtitleText(text, layout.maxLineDisplayWidth());
                double start = cursorSeconds;
                double end = cursorSeconds + durationSeconds;
                srt.append(subtitleIndex).append('\n')
                        .append(formatSrtTime(start)).append(" --> ").append(formatSrtTime(end)).append('\n')
                        .append(escapeSrtText(wrappedText)).append("\n\n");
                ass.append("Dialogue: 0,")
                        .append(formatAssTime(start)).append(',')
                        .append(formatAssTime(end))
                        .append(",Default,,0,0,0,,")
                        .append(escapeAssText(wrappedText))
                        .append('\n');
                hasSubtitle = true;
                subtitleIndex++;
            }
            cursorSeconds += durationSeconds;
        }

        Files.writeString(srtFile, srt.toString(), StandardCharsets.UTF_8);
        Files.writeString(assFile, ass.toString(), StandardCharsets.UTF_8);
        return new SubtitleFiles(srtFile, assFile, hasSubtitle);
    }

    static SubtitleLayout resolveSubtitleLayout(VideoDimensions dimensions) {
        // 行宽使用半个全角字符为一个单位，让中英文共用同一套安全宽度。
        VideoDimensions safeDimensions = dimensions != null && dimensions.isValid()
                ? dimensions
                : DEFAULT_VIDEO_DIMENSIONS;
        int width = safeDimensions.width();
        int height = safeDimensions.height();
        int shortSide = Math.min(width, height);
        int fontSize = Math.max(16, (int) Math.round(shortSide * SUBTITLE_FONT_SIZE_RATIO));
        int horizontalMargin = Math.max(fontSize,
                (int) Math.round(width * SUBTITLE_HORIZONTAL_MARGIN_RATIO));
        int verticalMargin = Math.max(fontSize,
                (int) Math.round(height * SUBTITLE_VERTICAL_MARGIN_RATIO));
        int usableWidth = Math.max(fontSize, width - horizontalMargin * 2);
        int maxLineDisplayWidth = Math.max(4, (int) Math.floor(
                usableWidth * 2.0 / fontSize * SUBTITLE_LINE_WIDTH_SAFETY_RATIO
        ));
        return new SubtitleLayout(width, height, fontSize, horizontalMargin, verticalMargin,
                maxLineDisplayWidth);
    }

    static String wrapSubtitleText(String text, int maxLineDisplayWidth) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int safeMaxWidth = Math.max(4, maxLineDisplayWidth);
        List<String> wrappedLines = new ArrayList<>();
        for (String paragraph : normalized.split("\n", -1)) {
            if (paragraph.isBlank()) {
                wrappedLines.add("");
            } else {
                wrappedLines.addAll(wrapSubtitleParagraph(paragraph.strip(), safeMaxWidth));
            }
        }
        return String.join("\n", wrappedLines);
    }

    private static List<String> wrapSubtitleParagraph(String paragraph, int maxLineDisplayWidth) {
        // 优先采用 Unicode 自然断行点；无断点的长文本再按完整 grapheme 硬拆。
        List<SubtitleGrapheme> graphemes = subtitleGraphemes(paragraph);
        if (graphemes.isEmpty()) {
            return List.of("");
        }

        boolean[] naturalBreaks = new boolean[paragraph.length() + 1];
        BreakIterator lineIterator = BreakIterator.getLineInstance(Locale.CHINA);
        lineIterator.setText(paragraph);
        for (int boundary = lineIterator.first(); boundary != BreakIterator.DONE;
             boundary = lineIterator.next()) {
            naturalBreaks[boundary] = true;
        }

        List<String> lines = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < graphemes.size()) {
            while (lineStart < graphemes.size() && graphemes.get(lineStart).whitespace()) {
                lineStart++;
            }
            if (lineStart >= graphemes.size()) {
                break;
            }

            int width = 0;
            int cursor = lineStart;
            int lastNaturalBreak = -1;
            while (cursor < graphemes.size()) {
                SubtitleGrapheme grapheme = graphemes.get(cursor);
                if (cursor > lineStart && width + grapheme.displayWidth() > maxLineDisplayWidth) {
                    break;
                }
                width += grapheme.displayWidth();
                cursor++;
                if (naturalBreaks[grapheme.end()]) {
                    lastNaturalBreak = cursor;
                }
            }

            if (cursor >= graphemes.size()) {
                String remaining = paragraph.substring(graphemes.get(lineStart).start()).strip();
                if (!remaining.isEmpty()) {
                    lines.add(remaining);
                }
                break;
            }

            int breakAt = lastNaturalBreak > lineStart ? lastNaturalBreak : cursor;
            if (breakAt <= lineStart) {
                breakAt = lineStart + 1;
            }
            String line = paragraph.substring(
                    graphemes.get(lineStart).start(),
                    graphemes.get(breakAt - 1).end()
            ).strip();
            if (!line.isEmpty()) {
                lines.add(line);
            }
            lineStart = breakAt;
        }
        return lines;
    }

    private static List<SubtitleGrapheme> subtitleGraphemes(String text) {
        List<SubtitleGrapheme> graphemes = new ArrayList<>();
        Matcher matcher = SUBTITLE_GRAPHEME_PATTERN.matcher(text);
        while (matcher.find()) {
            String grapheme = matcher.group();
            graphemes.add(new SubtitleGrapheme(
                    matcher.start(),
                    matcher.end(),
                    subtitleGraphemeDisplayWidth(grapheme),
                    grapheme.codePoints().allMatch(codePoint ->
                            Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))
            ));
        }
        return graphemes;
    }

    private static int subtitleGraphemeDisplayWidth(String grapheme) {
        boolean hasVisibleCodePoint = false;
        for (int offset = 0; offset < grapheme.length();) {
            int codePoint = grapheme.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.FORMAT) {
                if (codePoint == 0xFE0F || codePoint == 0x20E3) {
                    return 2;
                }
                continue;
            }
            hasVisibleCodePoint = true;
            if (isWideSubtitleCodePoint(codePoint)) {
                return 2;
            }
        }
        return hasVisibleCodePoint ? 1 : 0;
    }

    private static boolean isWideSubtitleCodePoint(int codePoint) {
        if ((codePoint >= 'A' && codePoint <= 'Z')
                || codePoint == 'm'
                || codePoint == 'w'
                || codePoint == '@'
                || codePoint == '%'
                || codePoint == '&') {
            return true;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.BOPOMOFO
                || script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.YI) {
            return true;
        }
        if ((codePoint >= 0x2E80 && codePoint <= 0x303F)
                || (codePoint >= 0xFE10 && codePoint <= 0xFE6F)
                || (codePoint >= 0xFF01 && codePoint <= 0xFF60)
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return codePoint > 0x7F && (type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.MATH_SYMBOL
                || type == Character.OTHER_SYMBOL);
    }

    private double resolveClipDurationSeconds(StoryboardItem item) {
        BigDecimal duration = item != null ? item.getDuration() : null;
        if (duration == null || duration.compareTo(BigDecimal.ZERO) <= 0) {
            return DEFAULT_CLIP_DURATION_SECONDS;
        }
        return duration.setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private String cleanDialogue(String dialogue) {
        if (!StringUtils.hasText(dialogue)) {
            return null;
        }
        String normalized = dialogue.replace("\r\n", "\n").replace('\r', '\n').trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if ("none".equals(lower) || "n/a".equals(lower) || "null".equals(lower)
                || "无".equals(normalized) || "无对白".equals(normalized) || "无台词".equals(normalized)) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                if (cleaned.length() > 0) {
                    cleaned.append('\n');
                }
                cleaned.append(trimmed);
            }
        }
        return cleaned.length() == 0 ? null : cleaned.toString();
    }

    private String escapeSrtText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String escapeAssText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\\N");
    }

    private String formatSrtTime(double seconds) {
        long millis = Math.max(0, Math.round(seconds * 1000));
        long hours = millis / 3_600_000;
        long minutes = (millis % 3_600_000) / 60_000;
        long secs = (millis % 60_000) / 1000;
        long ms = millis % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, secs, ms);
    }

    private String formatAssTime(double seconds) {
        long centis = Math.max(0, Math.round(seconds * 100));
        long hours = centis / 360_000;
        long minutes = (centis % 360_000) / 6_000;
        long secs = (centis % 6_000) / 100;
        long cs = centis % 100;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", hours, minutes, secs, cs);
    }

    private boolean runFfmpegConcatDemuxer(Path listFile, Path output) throws Exception {
        List<String> cmd = List.of(
                getFfmpegExecutable(), "-y",
                "-f", "concat", "-safe", "0",
                "-i", listFile.toString(),
                "-c", "copy",
                output.toString()
        );
        return runFfmpeg(cmd, "concat-demuxer", 15);
    }

    private boolean runFfmpegFilterConcat(List<Path> files, Path output) throws Exception {
        boolean includeAudio = allFilesHaveAudio(files);
        if (!includeAudio) {
            log.warn("[VideoCompose] 检测到至少一个输入无音轨，回退到仅视频重编码 concat");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(getFfmpegExecutable());
        cmd.add("-y");
        for (Path f : files) {
            cmd.add("-i");
            cmd.add(f.toString());
        }
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            filter.append("[").append(i).append(":v]");
            if (includeAudio) {
                filter.append("[").append(i).append(":a]");
            }
        }
        filter.append("concat=n=").append(files.size())
                .append(":v=1:a=").append(includeAudio ? 1 : 0)
                .append(includeAudio ? "[outv][outa]" : "[outv]");
        cmd.add("-filter_complex");
        cmd.add(filter.toString());
        cmd.add("-map");
        cmd.add("[outv]");
        if (includeAudio) {
            cmd.add("-map");
            cmd.add("[outa]");
        }
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        if (includeAudio) {
            cmd.add("-c:a");
            cmd.add("aac");
        }
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add(output.toString());
        return runFfmpeg(cmd, "filter-complex", 30);
    }

    private boolean runFfmpegTrim(Path input, Path output, double sourceStart, double duration,
                                  boolean audioEnabled, double audioVolume) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(getFfmpegExecutable(), "-y", "-ss", ffmpegNumber(sourceStart),
                "-i", input.toString(), "-t", ffmpegNumber(duration), "-map", "0:v:0"));
        if (audioEnabled) {
            cmd.addAll(List.of("-map", "0:a?", "-af", "volume=" + ffmpegNumber(audioVolume), "-c:a", "aac"));
        } else {
            cmd.add("-an");
        }
        cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", output.toString()));
        return runFfmpeg(cmd, "trim", 15);
    }

    private static double clampTrackVolume(Double value) {
        if (value == null || !Double.isFinite(value)) return 1.0;
        return Math.max(0, Math.min(MAX_AUDIO_VOLUME, value));
    }

    private static double finitePositiveOrZero(Double value) {
        return value != null && Double.isFinite(value) ? Math.max(0, value) : 0;
    }

    private static double finiteDuration(Double value, double fallback) {
        if (value == null || !Double.isFinite(value) || value <= 0) return fallback;
        return Math.min(value, 6 * 60 * 60);
    }

    private boolean runFfmpegPostProcess(Path input, Path assFile, Path bgmFile,
                                         List<EditorAudioFile> editorAudioFiles, Path output, double outputDuration,
                                         ComposeOptions options) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(getFfmpegExecutable());
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(input.toString());
        if (bgmFile != null) {
            cmd.add("-stream_loop");
            cmd.add("-1");
            cmd.add("-i");
            cmd.add(bgmFile.toString());
        }
        for (EditorAudioFile audioFile : editorAudioFiles) {
            cmd.add("-i");
            cmd.add(audioFile.path().toString());
        }

        boolean hasOriginalAudio = options.keepOriginalAudio() && hasAudioStream(input);
        boolean hasBgm = bgmFile != null;
        boolean burnSubtitles = options.burnSubtitles() && assFile != null && Files.exists(assFile);
        List<String> filters = new ArrayList<>();

        List<String> videoFilters = new ArrayList<>();
        if (burnSubtitles) videoFilters.add("subtitles='" + escapeSubtitlePath(assFile) + "'");
        double fadeInDuration = Math.min(options.fadeInDuration(), outputDuration / 2);
        double fadeOutDuration = Math.min(options.fadeOutDuration(), outputDuration / 2);
        if (fadeInDuration > 0) videoFilters.add("fade=t=in:st=0:d=" + ffmpegNumber(fadeInDuration));
        if (fadeOutDuration > 0) videoFilters.add("fade=t=out:st="
                + ffmpegNumber(Math.max(0, outputDuration - fadeOutDuration)) + ":d=" + ffmpegNumber(fadeOutDuration));
        boolean processVideo = !videoFilters.isEmpty();
        if (processVideo) {
            filters.add("[0:v]" + String.join(",", videoFilters) + "[outv]");
        }

        List<String> audioLabels = new ArrayList<>();
        if (hasOriginalAudio) {
            filters.add("[0:a]volume=" + ffmpegNumber(options.originalAudioVolume()) + "[originala]");
            audioLabels.add("[originala]");
        }
        int nextInputIndex = 1;
        if (hasBgm) {
            filters.add("[" + nextInputIndex + ":a]volume=" + ffmpegNumber(options.bgmVolume()) + "[bgma]");
            audioLabels.add("[bgma]");
            nextInputIndex++;
        }
        for (int i = 0; i < editorAudioFiles.size(); i++) {
            EditorAudio audio = editorAudioFiles.get(i).audio();
            long delayMs = Math.max(0, Math.round(audio.timelineStart() * 1000));
            String label = "editora" + i;
            filters.add("[" + (nextInputIndex + i) + ":a]atrim=start=" + ffmpegNumber(audio.sourceStart())
                    + ":duration=" + ffmpegNumber(audio.duration()) + ",asetpts=PTS-STARTPTS,volume="
                    + ffmpegNumber(audio.volume()) + ",adelay=" + delayMs + "|" + delayMs + "[" + label + "]");
            audioLabels.add("[" + label + "]");
        }
        String audioMap = null;
        if (audioLabels.size() == 1) {
            filters.add(audioLabels.get(0) + "anull[basea]");
        } else if (audioLabels.size() > 1) {
            filters.add(String.join("", audioLabels) + "amix=inputs=" + audioLabels.size()
                    + ":duration=first:dropout_transition=2[basea]");
        }
        if (!audioLabels.isEmpty()) {
            List<String> audioFades = new ArrayList<>();
            if (fadeInDuration > 0) audioFades.add("afade=t=in:st=0:d=" + ffmpegNumber(fadeInDuration));
            if (fadeOutDuration > 0) audioFades.add("afade=t=out:st="
                    + ffmpegNumber(Math.max(0, outputDuration - fadeOutDuration)) + ":d=" + ffmpegNumber(fadeOutDuration));
            filters.add("[basea]" + (audioFades.isEmpty() ? "anull" : String.join(",", audioFades)) + "[outa]");
            audioMap = "[outa]";
        }

        if (!filters.isEmpty()) {
            cmd.add("-filter_complex");
            cmd.add(String.join(";", filters));
        }

        cmd.add("-map");
        cmd.add(processVideo ? "[outv]" : "0:v");
        if (audioMap != null) {
            cmd.add("-map");
            cmd.add(audioMap);
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-shortest");
        } else {
            cmd.add("-an");
        }
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-t");
        cmd.add(ffmpegNumber(outputDuration));
        cmd.add(output.toString());
        return runFfmpeg(cmd, "post-process", 30);
    }

    private boolean runFfmpeg(List<String> cmd, String tag, int timeoutMinutes) throws Exception {
        log.info("[VideoCompose:{}] 执行: {}", tag, String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw wrapExecutableStartException(e, "ffmpeg", "video.compose.ffmpeg-path", getFfmpegExecutable());
        }
        StringBuffer outputTail = new StringBuffer();
        Thread outputReader = new Thread(() -> drainProcessOutput(p.getInputStream(), tag, outputTail),
                "ffmpeg-" + tag + "-output");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean done;
        try {
            done = p.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!done) {
            p.destroy();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            outputReader.join(TimeUnit.SECONDS.toMillis(5));
            log.error("[VideoCompose:{}] 超时，已强制终止。最近输出: {}", tag, summarizeOutputTail(outputTail));
            return false;
        }

        outputReader.join(TimeUnit.SECONDS.toMillis(5));
        int exit = p.exitValue();
        if (exit != 0) {
            log.error("[VideoCompose:{}] 退出码非 0: {}。最近输出: {}", tag, exit, summarizeOutputTail(outputTail));
            return false;
        }
        return true;
    }

    private void downloadToFile(String url, Path dest) throws IOException {
        if (url.startsWith(LOCAL_MEDIA_PUBLIC_PREFIX)) {
            Path local = resolveManagedMediaPath(url);
            if (Files.exists(local)) {
                Files.copy(local, dest, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            if (!isAbsoluteHttpUrl(url)) {
                throw new IOException("本地媒体文件不存在: " + local);
            }
            log.warn("[VideoCompose] /media/ 路径文件不存在，回退 HTTP: {}", url);
        }

        URI current = URI.create(url);
        int redirects = 0;
        while (true) {
            validateRemoteUri(current);
            HttpURLConnection conn = openConnection(current);
            try {
                int code = conn.getResponseCode();
                if (isRedirect(code)) {
                    if (redirects >= maxRedirects) {
                        throw new IOException("下载重定向次数过多: " + url);
                    }
                    String location = conn.getHeaderField("Location");
                    if (!StringUtils.hasText(location)) {
                        throw new IOException("下载重定向缺少 Location: " + url);
                    }
                    current = current.resolve(location);
                    redirects++;
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("下载失败 HTTP " + code + ": " + current);
                }
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            } finally {
                conn.disconnect();
            }
        }
    }

    private int markRunning(ComposeTarget target) {
        int updated = switch (target.type()) {
            case EPISODE -> episodeMapper.update(null, new UpdateWrapper<StoryboardEpisode>()
                    .eq("id", target.id())
                    .ne("compose_status", STATUS_RUNNING)
                    .set("compose_status", STATUS_RUNNING)
                    .set("compose_error_msg", null)
                    .set("composed_video_url", null)
                    .set("subtitle_srt_url", null)
                    .set("subtitle_ass_url", null)
                    .set("composed_at", null));
            case SCENE -> sceneMapper.update(null, new UpdateWrapper<StoryboardScene>()
                    .eq("id", target.id())
                    .ne("compose_status", STATUS_RUNNING)
                    .set("compose_status", STATUS_RUNNING)
                    .set("compose_error_msg", null)
                    .set("composed_video_url", null)
                    .set("subtitle_srt_url", null)
                    .set("subtitle_ass_url", null)
                    .set("composed_at", null));
        };
        if (updated > 0) {
            evictTargetCaches(target);
        }
        return updated;
    }

    private void markDone(ComposeTarget target, String videoUrl, String srtUrl, String assUrl) {
        if (target.type() == ComposeTargetType.EPISODE) {
            StoryboardEpisode update = new StoryboardEpisode();
            update.setId(target.id());
            update.setComposedVideoUrl(videoUrl);
            update.setSubtitleSrtUrl(srtUrl);
            update.setSubtitleAssUrl(assUrl);
            update.setComposeStatus(STATUS_DONE);
            update.setComposedAt(LocalDateTime.now());
            update.setComposeErrorMsg(null);
            episodeMapper.updateById(update);
        } else {
            StoryboardScene update = new StoryboardScene();
            update.setId(target.id());
            update.setComposedVideoUrl(videoUrl);
            update.setSubtitleSrtUrl(srtUrl);
            update.setSubtitleAssUrl(assUrl);
            update.setComposeStatus(STATUS_DONE);
            update.setComposedAt(LocalDateTime.now());
            update.setComposeErrorMsg(null);
            sceneMapper.updateById(update);
        }
        evictTargetCaches(target);
    }

    private void markFailed(ComposeTarget target, String msg) {
        try {
            String trimmed = msg == null ? "未知错误" : (msg.length() > 1000 ? msg.substring(0, 1000) : msg);
            if (target.type() == ComposeTargetType.EPISODE) {
                episodeMapper.update(null, new UpdateWrapper<StoryboardEpisode>()
                        .eq("id", target.id())
                        .set("compose_status", STATUS_FAILED)
                        .set("compose_error_msg", trimmed)
                        .set("composed_video_url", null)
                        .set("subtitle_srt_url", null)
                        .set("subtitle_ass_url", null)
                        .set("composed_at", null));
            } else {
                sceneMapper.update(null, new UpdateWrapper<StoryboardScene>()
                        .eq("id", target.id())
                        .set("compose_status", STATUS_FAILED)
                        .set("compose_error_msg", trimmed)
                        .set("composed_video_url", null)
                        .set("subtitle_srt_url", null)
                        .set("subtitle_ass_url", null)
                        .set("composed_at", null));
            }
            evictTargetCaches(target);
        } catch (Exception e) {
            log.error("[VideoCompose] 更新失败状态异常", e);
        }
    }

    private void evictTargetCaches(ComposeTarget target) {
        if (cacheManager == null) {
            return;
        }
        if (target.type() == ComposeTargetType.EPISODE) {
            evictCache("storyboardEpisode", target.id());
            evictCache("storyboardEpisode", "storyboard:" + target.storyboardId());
        } else {
            evictCache("storyboardScene", target.id());
            if (target.episodeId() != null) {
                evictCache("storyboardScene", "episode:" + target.episodeId());
            }
            if (target.storyboardId() != null) {
                evictCache("storyboardScene", "storyboard:" + target.storyboardId());
            }
        }
    }

    private void evictCache(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    private VideoDimensions probeVideoDimensions(Path file) {
        List<String> cmd = List.of(
                getFfprobeExecutable(), "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-of", "csv=s=x:p=0",
                file.toString()
        );
        try {
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean done = process.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                log.warn("[VideoCompose] ffprobe 检测视频尺寸超时，使用默认字幕画布: {}", file);
                return DEFAULT_VIDEO_DIMENSIONS;
            }
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            VideoDimensions dimensions = process.exitValue() == 0
                    ? parseVideoDimensions(output)
                    : null;
            if (dimensions == null) {
                log.warn("[VideoCompose] ffprobe 未返回有效视频尺寸，使用默认字幕画布: {}", file);
                return DEFAULT_VIDEO_DIMENSIONS;
            }
            return dimensions;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[VideoCompose] ffprobe 检测视频尺寸被中断，使用默认字幕画布: {}", file, e);
            return DEFAULT_VIDEO_DIMENSIONS;
        } catch (IOException e) {
            IOException wrapped = wrapExecutableStartException(
                    e,
                    "ffprobe",
                    "video.compose.ffprobe-path",
                    getFfprobeExecutable()
            );
            log.warn("[VideoCompose] ffprobe 不可用或尺寸检测失败，使用默认字幕画布: {}", file, wrapped);
            return DEFAULT_VIDEO_DIMENSIONS;
        }
    }

    static VideoDimensions parseVideoDimensions(String output) {
        if (!StringUtils.hasText(output)) {
            return null;
        }
        String line = output.strip().lines()
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("")
                .trim();
        String[] parts = line.split("[xX]", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            VideoDimensions dimensions = new VideoDimensions(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim())
            );
            return dimensions.isValid() ? dimensions : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean allFilesHaveAudio(List<Path> files) {
        for (Path file : files) {
            if (!hasAudioStream(file)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAudioStream(Path file) {
        List<String> cmd = List.of(
            getFfprobeExecutable(), "-v", "error",
                "-select_streams", "a",
                "-show_entries", "stream=index",
                "-of", "csv=p=0",
                file.toString()
        );
        try {
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean done = process.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                log.warn("[VideoCompose] ffprobe 检测音轨超时: {}", file);
                return false;
            }
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (process.exitValue() != 0) {
                log.warn("[VideoCompose] ffprobe 检测音轨失败，按无音轨处理: {}", file);
                return false;
            }
            return StringUtils.hasText(output.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[VideoCompose] ffprobe 检测音轨被中断: {}", file, e);
            return false;
        } catch (IOException e) {
            IOException wrapped = wrapExecutableStartException(
                    e,
                    "ffprobe",
                    "video.compose.ffprobe-path",
                    getFfprobeExecutable()
            );
            log.warn("[VideoCompose] ffprobe 不可用或检测失败，按无音轨处理: {}", file, wrapped);
            return false;
        }
    }

    private String getFfmpegExecutable() {
        return StringUtils.hasText(ffmpegPath) ? ffmpegPath.trim() : "ffmpeg";
    }

    private String getFfprobeExecutable() {
        return StringUtils.hasText(ffprobePath) ? ffprobePath.trim() : "ffprobe";
    }

    private IOException wrapExecutableStartException(IOException exception,
                                                     String executableName,
                                                     String propertyName,
                                                     String configuredValue) {
        if (!isExecutableMissing(exception)) {
            return exception;
        }
        return new IOException(buildMissingExecutableMessage(executableName, propertyName, configuredValue), exception);
    }

    private boolean isExecutableMissing(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (StringUtils.hasText(message)) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("createprocess error=2")
                        || normalized.contains("系统找不到指定的文件")
                        || normalized.contains("no such file or directory")
                        || normalized.contains("cannot run program")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String buildMissingExecutableMessage(String executableName,
                                                String propertyName,
                                                String configuredValue) {
        return "未找到 " + executableName + " 可执行文件，请先安装 " + executableName
                + "，或在配置中设置 " + propertyName + "。当前值：" + configuredValue;
    }

    private void drainProcessOutput(InputStream inputStream, String tag, StringBuffer outputTail) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendOutputTail(outputTail, line);
                if (log.isDebugEnabled()) {
                    log.debug("[ffmpeg:{}] {}", tag, line);
                }
            }
        } catch (IOException e) {
            log.warn("[VideoCompose:{}] 读取 ffmpeg 输出失败", tag, e);
        }
    }

    private void appendOutputTail(StringBuffer outputTail, String line) {
        synchronized (outputTail) {
            if (outputTail.length() > 0) {
                outputTail.append(System.lineSeparator());
            }
            outputTail.append(line);
            int maxLength = 4000;
            if (outputTail.length() > maxLength) {
                outputTail.delete(0, outputTail.length() - maxLength);
            }
        }
    }

    private String summarizeOutputTail(StringBuffer outputTail) {
        synchronized (outputTail) {
            return outputTail.isEmpty() ? "<no output>" : outputTail.toString();
        }
    }

    private Path resolveManagedMediaPath(String url) throws IOException {
        String rel = url.substring(LOCAL_MEDIA_PUBLIC_PREFIX.length());
        Path relativePath = Paths.get(rel).normalize();
        if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
            throw new IOException("非法媒体相对路径: " + rel);
        }

        Path basePath = resolveLocalMediaBasePath();
        Path resolved = basePath.resolve(relativePath).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new IOException("媒体路径越界: " + rel);
        }
        return resolved;
    }

    private Path resolveLocalMediaBasePath() {
        String basePath = mediaLocalPath;
        try {
            StorageConfig config = storageConfigService.getDefaultConfig();
            if (config != null
                    && "local".equalsIgnoreCase(config.getType())
                    && StringUtils.hasText(config.getBasePath())) {
                basePath = config.getBasePath();
            }
        } catch (Exception e) {
            log.debug("[VideoCompose] 读取默认存储配置失败，回退到 application 配置路径: {}", basePath, e);
        }
        return Paths.get(basePath).toAbsolutePath().normalize();
    }

    private HttpURLConnection openConnection(URI uri) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(180000);
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private void validateRemoteUri(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IOException("仅允许下载 http/https 资源: " + uri);
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IOException("下载地址缺少 host: " + uri);
        }

        String normalizedHost = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        Set<String> allowedHosts = parseAllowedHosts();
        if (!allowedHosts.isEmpty()) {
            if (!matchesAllowedHost(normalizedHost, allowedHosts)) {
                throw new IOException("下载地址 host 不在白名单: " + normalizedHost);
            }
            return;
        }

        InetAddress[] addresses = InetAddress.getAllByName(normalizedHost);
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IOException("拒绝访问内网或本地地址: " + normalizedHost);
            }
        }
    }

    private Set<String> parseAllowedHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        if (!StringUtils.hasText(allowedHostsConfig)) {
            return hosts;
        }
        for (String token : allowedHostsConfig.split(",")) {
            String trimmed = token.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }
        return hosts;
    }

    private boolean matchesAllowedHost(String host, Set<String> allowedHosts) {
        for (String pattern : allowedHosts) {
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                if (host.endsWith(suffix)) {
                    return true;
                }
                continue;
            }
            if (host.equals(pattern) || host.endsWith("." + pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == HttpURLConnection.HTTP_MOVED_PERM
                || statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                || statusCode == HttpURLConnection.HTTP_SEE_OTHER
                || statusCode == 307
                || statusCode == 308;
    }

    private boolean isAbsoluteHttpUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String resolveMediaExtension(String url, String defaultExt) {
        String fallback = normalizeExtension(defaultExt);
        if (!StringUtils.hasText(url)) {
            return "." + fallback;
        }
        String path = url;
        try {
            URI uri = URI.create(url);
            if (StringUtils.hasText(uri.getPath())) {
                path = uri.getPath();
            }
        } catch (Exception ignored) {
            int queryIndex = path.indexOf('?');
            if (queryIndex >= 0) {
                path = path.substring(0, queryIndex);
            }
            int fragmentIndex = path.indexOf('#');
            if (fragmentIndex >= 0) {
                path = path.substring(0, fragmentIndex);
            }
        }
        int slashIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex <= slashIndex || dotIndex >= path.length() - 1) {
            return "." + fallback;
        }
        String ext = normalizeExtension(path.substring(dotIndex + 1));
        if (!StringUtils.hasText(ext) || ext.length() > 8) {
            return "." + fallback;
        }
        return "." + ext;
    }

    private String normalizeExtension(String ext) {
        String value = StringUtils.hasText(ext) ? ext.trim() : "bin";
        if (value.startsWith(".")) {
            value = value.substring(1);
        }
        value = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return StringUtils.hasText(value) ? value : "bin";
    }

    private static boolean isDefaultVolume(double volume) {
        return Math.abs(volume - DEFAULT_ORIGINAL_AUDIO_VOLUME) < 0.0001;
    }

    private String ffmpegNumber(double value) {
        double safeValue = Double.isFinite(value) ? value : DEFAULT_ORIGINAL_AUDIO_VOLUME;
        return BigDecimal.valueOf(safeValue)
                .setScale(3, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private String escapeSubtitlePath(Path assFile) {
        String path = assFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        return path.replace(":", "\\:").replace("'", "\\'");
    }

    public static record ComposeOptions(boolean generateSubtitleFiles,
                                        boolean burnSubtitles,
                                        boolean keepOriginalAudio,
                                        double originalAudioVolume,
                                        String bgmUrl,
                                        double bgmVolume,
                                        double fadeInDuration,
                                        double fadeOutDuration,
                                        List<EditorAudio> editorAudios) {
        public static ComposeOptions defaults() {
            return new ComposeOptions(
                    true,
                    true,
                    true,
                    DEFAULT_ORIGINAL_AUDIO_VOLUME,
                    null,
                    DEFAULT_BGM_VOLUME,
                    0,
                    0,
                    List.of()
            );
        }

        public static ComposeOptions from(ComposeEpisodeVideoReqVO reqVO) {
            ComposeOptions defaults = defaults();
            if (reqVO == null) {
                return defaults;
            }
            return new ComposeOptions(
                    boolOrDefault(reqVO.getGenerateSubtitleFiles(), defaults.generateSubtitleFiles()),
                    boolOrDefault(reqVO.getBurnSubtitles(), defaults.burnSubtitles()),
                    boolOrDefault(reqVO.getKeepOriginalAudio(), defaults.keepOriginalAudio()),
                    clampVolume(reqVO.getOriginalAudioVolume(), defaults.originalAudioVolume()),
                    trimToNull(reqVO.getBgmUrl()),
                    clampVolume(reqVO.getBgmVolume(), defaults.bgmVolume()),
                    clampFadeDuration(reqVO.getFadeInDuration()),
                    clampFadeDuration(reqVO.getFadeOutDuration()),
                    List.of()
            );
        }

        private static boolean boolOrDefault(Boolean value, boolean fallback) {
            return value != null ? value : fallback;
        }

        private static double clampVolume(Double value, double fallback) {
            if (value == null || !Double.isFinite(value)) {
                return fallback;
            }
            return Math.max(0, Math.min(MAX_AUDIO_VOLUME, value));
        }

        private static String trimToNull(String value) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            return value.trim();
        }

        private static double clampFadeDuration(Double value) {
            if (value == null || !Double.isFinite(value)) return 0;
            return Math.max(0, Math.min(60, value));
        }

        public ComposeOptions withEditorAudios(List<EditorAudio> audios) {
            return new ComposeOptions(generateSubtitleFiles, burnSubtitles, keepOriginalAudio,
                    originalAudioVolume, bgmUrl, bgmVolume, fadeInDuration, fadeOutDuration,
                    List.copyOf(audios));
        }
    }

    public static record EditorAudio(String url, double sourceStart, double duration,
                                     double timelineStart, double volume) {
    }

    private record EditorAudioFile(Path path, EditorAudio audio) {
    }

    private enum ComposeTargetType {
        EPISODE,
        SCENE
    }

    private record ComposeTarget(ComposeTargetType type,
                                 Long id,
                                 Long projectId,
                                 Long storyboardId,
                                 Long episodeId,
                                 String taskType,
                                 String taskContextType,
                                 String taskTitle,
                                 String noClipsMessage,
                                 String busyMessage,
                                 String logLabel,
                                 String workDirPrefix) {
    }

    private record ComposeClip(StoryboardItem item, String videoUrl, double sourceStart, double duration,
                               boolean audioEnabled, double audioVolume) {
        boolean requiresProcessing() {
            return sourceStart > 0.0001 || Math.abs(duration - resolveItemDuration(item)) > 0.0001
                    || !audioEnabled || !isDefaultVolume(audioVolume);
        }

        private static double resolveItemDuration(StoryboardItem item) {
            BigDecimal value = item != null ? item.getDuration() : null;
            return value != null && value.compareTo(BigDecimal.ZERO) > 0 ? value.doubleValue() : DEFAULT_CLIP_DURATION_SECONDS;
        }
    }

    private record SubtitleFiles(Path srtFile, Path assFile, boolean hasSubtitle) {
    }

    record VideoDimensions(int width, int height) {
        boolean isValid() {
            return width > 0 && height > 0
                    && width <= MAX_PROBED_VIDEO_DIMENSION
                    && height <= MAX_PROBED_VIDEO_DIMENSION;
        }
    }

    record SubtitleLayout(int playResX,
                          int playResY,
                          int fontSize,
                          int horizontalMargin,
                          int verticalMargin,
                          int maxLineDisplayWidth) {
    }

    private record SubtitleGrapheme(int start, int end, int displayWidth, boolean whitespace) {
    }
}
