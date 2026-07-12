package com.stonewu.fusion.service.generation;

import com.stonewu.fusion.service.storage.MediaStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoTailFrameService {
    private final MediaStorageService mediaStorageService;

    @Value("${video.compose.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    public String extractAndStore(String videoUrl) {
        if (!StringUtils.hasText(videoUrl)) return null;
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("video_tail_frame_");
            Path output = workDir.resolve("tail.jpg");
            List<String> command = List.of(ffmpegPath, "-y", "-sseof", "-0.2", "-i", videoUrl,
                    "-frames:v", "1", "-q:v", "2", output.toString());
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                log.warn("[VideoTailFrame] FFmpeg 提取尾帧超时: {}", videoUrl);
                return null;
            }
            if (process.exitValue() != 0 || !Files.exists(output) || Files.size(output) == 0) {
                log.warn("[VideoTailFrame] FFmpeg 提取尾帧失败: exitCode={}, url={}", process.exitValue(), videoUrl);
                return null;
            }
            String storedUrl = mediaStorageService.storeFile(output, "images/video-tail-frames", "jpg");
            log.info("[VideoTailFrame] 尾帧已提取并保存: {}", storedUrl);
            return storedUrl;
        } catch (Exception e) {
            log.warn("[VideoTailFrame] 尾帧提取异常: url={}, reason={}", videoUrl, e.getMessage());
            return null;
        } finally {
            if (workDir != null) {
                try (var paths = Files.walk(workDir)) {
                    paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                } catch (Exception ignored) { }
            }
        }
    }
}
