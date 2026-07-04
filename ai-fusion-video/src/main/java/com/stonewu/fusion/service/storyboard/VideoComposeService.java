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
import com.stonewu.fusion.service.storage.MediaStorageService;
import com.stonewu.fusion.service.storage.StorageConfigService;
import com.stonewu.fusion.service.task.TaskStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
    private static final String TASK_TYPE = "storyboard_episode_compose";
    private static final String TASK_CONTEXT_TYPE = "storyboard_episode";
    private static final String TASK_INITIAL_MESSAGE = "已提交合成任务，正在拼接镜头视频…";

    public static final int STATUS_IDLE = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_FAILED = 3;

    private static final double DEFAULT_CLIP_DURATION_SECONDS = 5.0;
    private static final double DEFAULT_ORIGINAL_AUDIO_VOLUME = 1.0;
    private static final double DEFAULT_BGM_VOLUME = 0.25;
    private static final double MAX_AUDIO_VOLUME = 2.0;

    private final StoryboardService storyboardService;
    private final StoryboardEpisodeMapper episodeMapper;
    private final MediaStorageService mediaStorageService;
    private final StorageConfigService storageConfigService;
    private final TaskStreamService taskStreamService;
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
                               MediaStorageService mediaStorageService,
                               StorageConfigService storageConfigService,
                               TaskStreamService taskStreamService,
                               @Qualifier("videoComposeExecutor") Executor videoComposeExecutor) {
        this.storyboardService = storyboardService;
        this.episodeMapper = episodeMapper;
        this.mediaStorageService = mediaStorageService;
        this.storageConfigService = storageConfigService;
        this.taskStreamService = taskStreamService;
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
        ComposeOptions effectiveOptions = options != null ? options : ComposeOptions.defaults();
        StoryboardEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BusinessException(404, "分镜集不存在: " + episodeId);
        }

        Storyboard storyboard = storyboardService.getById(episode.getStoryboardId());
        if (storyboard == null) {
            throw new BusinessException(404, "分镜不存在: " + episode.getStoryboardId());
        }

        String taskId = taskStreamService.createTask(
                userId,
                storyboard.getProjectId(),
                TASK_TYPE,
                buildTaskTitle(episode),
                TASK_CONTEXT_TYPE,
                episodeId,
                TASK_INITIAL_MESSAGE
        );

        List<ComposeClip> clips = collectComposeClips(episodeId);
        if (clips.isEmpty()) {
            String message = "本集没有可合成的视频，请先生成镜头视频";
            markFailed(episodeId, message);
            taskStreamService.fail(taskId, message);
            return taskId;
        }

        int updated = episodeMapper.update(null, new UpdateWrapper<StoryboardEpisode>()
            .eq("id", episodeId)
            .ne("compose_status", STATUS_RUNNING)
            .set("compose_status", STATUS_RUNNING)
            .set("compose_error_msg", null)
            .set("composed_video_url", null)
            .set("subtitle_srt_url", null)
            .set("subtitle_ass_url", null)
            .set("composed_at", null));
        if (updated == 0) {
            taskStreamService.fail(taskId, "本集已在合成中，请稍候");
            return taskId;
        }

        try {
            videoComposeExecutor.execute(() -> {
                try {
                    doCompose(episodeId, taskId, clips, effectiveOptions);
                } catch (Throwable t) {
                    String errorMessage = resolveErrorMessage(t);
                    log.error("[VideoCompose] 合成失败: episodeId={}", episodeId, t);
                    markFailed(episodeId, errorMessage);
                    taskStreamService.fail(taskId, errorMessage);
                }
            });
        } catch (RejectedExecutionException e) {
            String message = "合成队列繁忙，请稍后重试";
            markFailed(episodeId, message);
            taskStreamService.fail(taskId, message);
        }
        return taskId;
    }

    private void doCompose(Long episodeId, String taskId, List<ComposeClip> clips,
                           ComposeOptions options) throws Exception {
        log.info("[VideoCompose] 开始合成 episodeId={}, taskId={}", episodeId, taskId);
        long startMs = System.currentTimeMillis();
        log.info("[VideoCompose] episodeId={}, 待合成视频数={}, options={}", episodeId, clips.size(), options);

        Path workDir = Files.createTempDirectory("compose_ep_" + episodeId + "_");
        try {
            List<Path> localFiles = new ArrayList<>();
            for (int i = 0; i < clips.size(); i++) {
                Path local = workDir.resolve(String.format("v%04d.mp4", i));
                downloadToFile(clips.get(i).videoUrl(), local);
                localFiles.add(local);
            }

            SubtitleFiles subtitleFiles = buildSubtitleFiles(workDir, clips);

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
                log.warn("[VideoCompose] concat demuxer 失败，回退到 filter_complex episodeId={}", episodeId);
                ok = runFfmpegFilterConcat(localFiles, output);
            }
            if (!ok || !Files.exists(output) || Files.size(output) == 0) {
                throw new RuntimeException("ffmpeg 合成失败（concat 与 filter 均失败）");
            }

            Path bgmFile = null;
            if (StringUtils.hasText(options.bgmUrl())) {
                bgmFile = workDir.resolve("bgm" + resolveMediaExtension(options.bgmUrl(), "mp3"));
                downloadToFile(options.bgmUrl(), bgmFile);
            }

            Path finalOutput = output;
            boolean shouldBurnSubtitles = options.burnSubtitles() && subtitleFiles.hasSubtitle();
            if (shouldBurnSubtitles || bgmFile != null
                    || !options.keepOriginalAudio()
                    || !isDefaultVolume(options.originalAudioVolume())) {
                Path processed = workDir.resolve("output_processed.mp4");
                ok = runFfmpegPostProcess(output, shouldBurnSubtitles ? subtitleFiles.assFile() : null,
                        bgmFile, processed, options);
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

            StoryboardEpisode update = new StoryboardEpisode();
            update.setId(episodeId);
            update.setComposedVideoUrl(storedUrl);
            update.setSubtitleSrtUrl(srtUrl);
            update.setSubtitleAssUrl(assUrl);
            update.setComposeStatus(STATUS_DONE);
            update.setComposedAt(LocalDateTime.now());
            update.setComposeErrorMsg(null);
            episodeMapper.updateById(update);

            String subtitleText = StringUtils.hasText(srtUrl) ? " · 字幕：" + srtUrl : "";
            taskStreamService.complete(taskId, "✓ 合成完成 · 视频地址：" + storedUrl + subtitleText);

            log.info("[VideoCompose] 完成 episodeId={}, 耗时={}ms, 视频数={}",
                    episodeId, System.currentTimeMillis() - startMs, clips.size());
        } finally {
            try {
                FileSystemUtils.deleteRecursively(workDir.toFile());
            } catch (Exception e) {
                log.warn("[VideoCompose] 临时目录清理失败 {}", workDir, e);
            }
        }
    }

    private String buildTaskTitle(StoryboardEpisode episode) {
        String episodeLabel = StringUtils.hasText(episode.getTitle())
                ? episode.getTitle().trim()
                : (episode.getEpisodeNumber() != null
                ? "第 " + episode.getEpisodeNumber() + " 集"
                : "集 " + episode.getId());
        return "合成本集视频：" + episodeLabel;
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

    private List<ComposeClip> collectComposeClips(Long episodeId) {
        List<StoryboardScene> scenes = new ArrayList<>(storyboardService.listScenesByEpisode(episodeId));
        scenes.sort(Comparator.comparing(s -> Optional.ofNullable(s.getSortOrder()).orElse(0)));

        List<ComposeClip> clips = new ArrayList<>();
        for (StoryboardScene scene : scenes) {
            List<StoryboardItem> items = new ArrayList<>(storyboardService.listItemsByScene(scene.getId()));
            items.sort(Comparator.comparing(i -> Optional.ofNullable(i.getSortOrder()).orElse(0)));
            for (StoryboardItem item : items) {
                String url = StringUtils.hasText(item.getVideoUrl())
                        ? item.getVideoUrl()
                        : item.getGeneratedVideoUrl();
                if (StringUtils.hasText(url)) {
                    clips.add(new ComposeClip(item, url));
                }
            }
        }
        return clips;
    }

    private SubtitleFiles buildSubtitleFiles(Path workDir, List<ComposeClip> clips) throws IOException {
        Path srtFile = workDir.resolve("subtitles.srt");
        Path assFile = workDir.resolve("subtitles.ass");

        StringBuilder srt = new StringBuilder();
        StringBuilder ass = new StringBuilder();
        ass.append("[Script Info]\n")
                .append("ScriptType: v4.00+\n")
                .append("PlayResX: 1920\n")
                .append("PlayResY: 1080\n")
                .append("ScaledBorderAndShadow: yes\n\n")
                .append("[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, ")
                .append("Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, ")
                .append("Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Default,Arial,54,&H00FFFFFF,&H00FFFFFF,&H80000000,&H80000000,")
                .append("0,0,0,0,100,100,0,0,1,2,0,2,80,80,70,1\n\n")
                .append("[Events]\n")
                .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");

        boolean hasSubtitle = false;
        double cursorSeconds = 0;
        int subtitleIndex = 1;
        for (ComposeClip clip : clips) {
            double durationSeconds = resolveClipDurationSeconds(clip.item());
            String text = cleanDialogue(clip.item().getDialogue());
            if (StringUtils.hasText(text)) {
                double start = cursorSeconds;
                double end = cursorSeconds + durationSeconds;
                srt.append(subtitleIndex).append('\n')
                        .append(formatSrtTime(start)).append(" --> ").append(formatSrtTime(end)).append('\n')
                        .append(escapeSrtText(text)).append("\n\n");
                ass.append("Dialogue: 0,")
                        .append(formatAssTime(start)).append(',')
                        .append(formatAssTime(end))
                        .append(",Default,,0,0,0,,")
                        .append(escapeAssText(text))
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

    private boolean runFfmpegPostProcess(Path input, Path assFile, Path bgmFile, Path output,
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

        boolean hasOriginalAudio = options.keepOriginalAudio() && hasAudioStream(input);
        boolean hasBgm = bgmFile != null;
        boolean burnSubtitles = options.burnSubtitles() && assFile != null && Files.exists(assFile);
        List<String> filters = new ArrayList<>();

        if (burnSubtitles) {
            filters.add("[0:v]subtitles='" + escapeSubtitlePath(assFile) + "'[outv]");
        }

        String audioMap = null;
        if (hasOriginalAudio && hasBgm) {
            filters.add("[0:a]volume=" + ffmpegNumber(options.originalAudioVolume()) + "[a0]");
            filters.add("[1:a]volume=" + ffmpegNumber(options.bgmVolume()) + "[a1]");
            filters.add("[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[outa]");
            audioMap = "[outa]";
        } else if (hasOriginalAudio) {
            filters.add("[0:a]volume=" + ffmpegNumber(options.originalAudioVolume()) + "[outa]");
            audioMap = "[outa]";
        } else if (hasBgm) {
            filters.add("[1:a]volume=" + ffmpegNumber(options.bgmVolume()) + "[outa]");
            audioMap = "[outa]";
        }

        if (!filters.isEmpty()) {
            cmd.add("-filter_complex");
            cmd.add(String.join(";", filters));
        }

        cmd.add("-map");
        cmd.add(burnSubtitles ? "[outv]" : "0:v");
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

    private void markFailed(Long episodeId, String msg) {
        try {
            String trimmed = msg == null ? "未知错误" : (msg.length() > 1000 ? msg.substring(0, 1000) : msg);
            episodeMapper.update(null, new UpdateWrapper<StoryboardEpisode>()
                    .eq("id", episodeId)
                    .set("compose_status", STATUS_FAILED)
                    .set("compose_error_msg", trimmed)
                    .set("composed_video_url", null)
                    .set("subtitle_srt_url", null)
                    .set("subtitle_ass_url", null)
                    .set("composed_at", null));
        } catch (Exception e) {
            log.error("[VideoCompose] 更新失败状态异常", e);
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

    private boolean isDefaultVolume(double volume) {
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
                                        double bgmVolume) {
        public static ComposeOptions defaults() {
            return new ComposeOptions(
                    true,
                    false,
                    true,
                    DEFAULT_ORIGINAL_AUDIO_VOLUME,
                    null,
                    DEFAULT_BGM_VOLUME
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
                    clampVolume(reqVO.getBgmVolume(), defaults.bgmVolume())
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
    }

    private record ComposeClip(StoryboardItem item, String videoUrl) {
    }

    private record SubtitleFiles(Path srtFile, Path assFile, boolean hasSubtitle) {
    }
}
